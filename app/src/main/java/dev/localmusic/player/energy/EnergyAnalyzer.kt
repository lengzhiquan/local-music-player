package dev.localmusic.player.energy

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.localmusic.player.lyrics.ChannelSource
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EnergyAnalyzer {
    fun analyze(context: Context, uri: Uri): EnergyFeatures {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("缺少路径")
            return analyzeFile(File(path))
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法打开音频")
        try {
            return analyzePfd(pfd)
        } finally {
            pfd.close()
        }
    }

    fun analyzeFile(file: File): EnergyFeatures {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            return analyzePfd(pfd)
        }
    }

    private fun analyzePfd(pfd: ParcelFileDescriptor): EnergyFeatures {
        val channel = FileInputStream(pfd.fileDescriptor).channel
        val source = ChannelSource(channel)
        val wav = WavPcm.parse(source)
        if (wav != null && (wav.audioFormat == 1 || wav.audioFormat == 3)) {
            val parts = PcmSampler.plan(wav.totalFrames, wav.sampleRate).map { range ->
                WavPcm.readMono(source, wav, range.first, (range.last - range.first + 1).toInt())
            }
            val samples = PcmSampler.concat(parts)
            return EnergyMath.features(samples, wav.sampleRate)
        }
        return decodeWithCodec(pfd)
    }

    private fun decodeWithCodec(pfd: ParcelFileDescriptor): EnergyFeatures {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            val audioFormat = format ?: error("没有音频轨")
            extractor.selectTrack(track)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: error("未知编码")
            if (mime == "audio/raw" || mime == "audio/wav" || mime == "audio/x-wav") {
                return decodeRawExtractor(extractor, audioFormat)
            }
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val sampleRateHint = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val windows = if (durationUs <= 0L) {
                listOf(0L to 12_000_000L)
            } else if (durationUs <= 12_000_000L) {
                listOf(0L to durationUs)
            } else {
                listOf(0.15, 0.45, 0.72).map { fraction ->
                    val start = (durationUs * fraction).toLong()
                    val window = 4_000_000L
                    val clamped = start.coerceIn(0L, (durationUs - window).coerceAtLeast(0L))
                    clamped to (clamped + window).coerceAtMost(durationUs)
                }
            }
            val parts = ArrayList<FloatArray>()
            var rate = sampleRateHint
            for ((startUs, endUs) in windows) {
                val decoded = decodeWindow(extractor, audioFormat, mime, startUs, endUs)
                rate = decoded.sampleRate
                parts += decoded.samples
            }
            return EnergyMath.features(PcmSampler.concat(parts), rate)
        } finally {
            extractor.release()
        }
    }

    private data class Decoded(val samples: FloatArray, val sampleRate: Int)

    private fun decodeWindow(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
        startUs: Long,
        endUs: Long,
    ): Decoded {
        val codec = MediaCodec.createDecoderByType(normalizeMime(mime))
        try {
            rewindCodecSpecificData(format)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val samples = ArrayList<Float>(48_000 * 4)
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var encoding = 2 // PCM 16-bit
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var spins = 0
            while (!outputDone && spins++ < 20_000 && samples.size < 48000 * 16) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(8_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time < 0 || time > endUs + 200_000) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, 8_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 && info.presentationTimeUs >= startUs - 20_000) {
                            appendPcm(buffer, info.offset, info.size, channels, encoding, samples)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pastEnd = info.presentationTimeUs > endUs
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos || pastEnd) outputDone = true
                    }
                }
            }
            return Decoded(samples.toFloatArray(), sampleRate.coerceAtLeast(1))
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun decodeRawExtractor(extractor: MediaExtractor, format: MediaFormat): EnergyFeatures {
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44100
        }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            1
        }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val ranges = if (durationUs <= 12_000_000L) listOf(0L to Long.MAX_VALUE) else listOf(
            (durationUs * 0.15).toLong() to (durationUs * 0.15).toLong() + 4_000_000L,
            (durationUs * 0.45).toLong() to (durationUs * 0.45).toLong() + 4_000_000L,
            (durationUs * 0.72).toLong() to (durationUs * 0.72).toLong() + 4_000_000L,
        )
        val all = ArrayList<Float>()
        val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for ((start, end) in ranges) {
            extractor.seekTo(start, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var guard = 0
            while (guard++ < 20_000) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > end) break
                buffer.position(0)
                buffer.limit(size)
                val frames = size / (2 * channels)
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        if (buffer.remaining() < 2) break
                        sum += buffer.short / 32768f
                    }
                    all += sum / channels
                }
                extractor.advance()
            }
        }
        return EnergyMath.features(all.toFloatArray(), sampleRate)
    }

    private fun appendPcm(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        encoding: Int,
        out: MutableList<Float>,
    ) {
        val slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        slice.position(offset)
        slice.limit(offset + size)
        val ch = channels.coerceAtLeast(1)
        if (encoding == 4) { // PCM float
            val frames = slice.remaining() / (4 * ch)
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    if (slice.remaining() < 4) break
                    sum += slice.float
                }
                out += sum / ch
            }
        } else {
            val frames = slice.remaining() / (2 * ch)
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    if (slice.remaining() < 2) break
                    sum += slice.short / 32768f
                }
                out += sum / ch
            }
        }
    }

    private fun rewindCodecSpecificData(format: MediaFormat) {
        var index = 0
        while (true) {
            val key = "csd-$index"
            if (!format.containsKey(key)) break
            format.getByteBuffer(key)?.rewind()
            index++
        }
    }

    private fun normalizeMime(mime: String): String {
        return when (mime) {
            "audio/x-flac" -> "audio/flac"
            "audio/vorbis" -> "audio/vorbis"
            else -> mime
        }
    }
}
