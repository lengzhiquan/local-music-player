package dev.localmusic.player.energy

import dev.localmusic.player.lyrics.RandomRead
import dev.localmusic.player.lyrics.le32
import dev.localmusic.player.lyrics.readBytes
import dev.localmusic.player.lyrics.startsWithAscii
import kotlin.math.min

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val audioFormat: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val frameSize: Int get() = (channels * bitsPerSample / 8).coerceAtLeast(1)
    val totalFrames: Long get() = if (frameSize == 0) 0 else dataBytes / frameSize
}

object WavPcm {
    fun parse(src: RandomRead): WavInfo? {
        val riff = src.readBytes(0, 12) ?: return null
        if (!riff.startsWithAscii("RIFF") || !riff.startsWithAscii("WAVE", 8)) return null
        var pos = 12L
        var audioFormat = 1
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var dataOffset = -1L
        var dataBytes = 0L
        while (pos + 8 <= src.size) {
            val hdr = src.readBytes(pos, 8) ?: break
            val id = hdr.decodeToString(0, 4)
            val chunkSize = le32(hdr, 4).toLong() and 0xFFFFFFFFL
            val dataPos = pos + 8
            when (id) {
                "fmt " -> {
                    val fmt = src.readBytes(dataPos, min(chunkSize, 64L).toInt()) ?: return null
                    if (fmt.size >= 16) {
                        audioFormat = (fmt[0].toInt() and 0xFF) or ((fmt[1].toInt() and 0xFF) shl 8)
                        channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                        sampleRate = le32(fmt, 4)
                        bits = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                    }
                }
                "data" -> {
                    dataOffset = dataPos
                    dataBytes = chunkSize
                }
            }
            pos = dataPos + chunkSize + (chunkSize and 1L)
            if (dataOffset >= 0 && id == "data") break
        }
        if (dataOffset < 0 || channels <= 0 || sampleRate <= 0 || bits !in setOf(8, 16, 24, 32)) return null
        if (audioFormat != 1 && audioFormat != 3) return null
        return WavInfo(sampleRate, channels, bits, audioFormat, dataOffset, dataBytes)
    }

    fun readMono(src: RandomRead, info: WavInfo, startFrame: Long, frameCount: Int): FloatArray {
        if (frameCount <= 0) return FloatArray(0)
        val framesAvailable = (info.totalFrames - startFrame).coerceAtLeast(0)
        val frames = min(frameCount.toLong(), framesAvailable).toInt()
        if (frames <= 0) return FloatArray(0)
        val byteCount = frames * info.frameSize
        val raw = src.readBytes(info.dataOffset + startFrame * info.frameSize, byteCount) ?: return FloatArray(0)
        val actualFrames = raw.size / info.frameSize
        val out = FloatArray(actualFrames)
        val channels = info.channels
        for (frame in 0 until actualFrames) {
            var sum = 0f
            val base = frame * info.frameSize
            for (channel in 0 until channels) {
                val offset = base + channel * (info.bitsPerSample / 8)
                sum += sampleAt(raw, offset, info)
            }
            out[frame] = sum / channels
        }
        return out
    }

    private fun sampleAt(raw: ByteArray, offset: Int, info: WavInfo): Float {
        if (info.audioFormat == 3 && info.bitsPerSample == 32 && offset + 4 <= raw.size) {
            val bits = (raw[offset].toInt() and 0xFF) or
                ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                ((raw[offset + 2].toInt() and 0xFF) shl 16) or
                ((raw[offset + 3].toInt() and 0xFF) shl 24)
            return Float.fromBits(bits)
        }
        return when (info.bitsPerSample) {
            8 -> {
                if (offset >= raw.size) 0f
                else ((raw[offset].toInt() and 0xFF) - 128) / 128f
            }
            24 -> {
                if (offset + 3 > raw.size) 0f
                else {
                    val value = (raw[offset].toInt() and 0xFF) or
                        ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                        ((raw[offset + 2].toInt() and 0xFF) shl 16)
                    val signed = if (value and 0x800000 != 0) value or -0x1000000 else value
                    signed / 8388608f
                }
            }
            32 -> {
                if (offset + 4 > raw.size) 0f
                else {
                    val value = (raw[offset].toInt() and 0xFF) or
                        ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                        ((raw[offset + 2].toInt() and 0xFF) shl 16) or
                        ((raw[offset + 3].toInt() and 0xFF) shl 24)
                    value / 2147483648f
                }
            }
            else -> {
                if (offset + 2 > raw.size) 0f
                else {
                    val value = (raw[offset].toInt() and 0xFF) or ((raw[offset + 1].toInt() and 0xFF) shl 8)
                    val signed = value.toShort().toInt()
                    signed / 32768f
                }
            }
        }
    }
}

object PcmSampler {
    /** Frame ranges to decode. Short files are read whole; longer files contribute three 4-second windows. */
    fun plan(totalFrames: Long, sampleRate: Int): List<LongRange> {
        if (totalFrames <= 0 || sampleRate <= 0) return emptyList()
        val window = sampleRate.toLong() * 4L
        if (totalFrames <= window * 3) return listOf(0L until totalFrames)
        val starts = doubleArrayOf(0.15, 0.45, 0.72).map { fraction ->
            val start = (totalFrames * fraction).toLong()
            start.coerceIn(0L, (totalFrames - window).coerceAtLeast(0L))
        }.distinct()
        return starts.map { start -> start until (start + window).coerceAtMost(totalFrames) }
    }

    fun concat(parts: List<FloatArray>): FloatArray {
        val size = parts.sumOf { it.size }
        val out = FloatArray(size)
        var cursor = 0
        for (part in parts) {
            part.copyInto(out, cursor)
            cursor += part.size
        }
        return out
    }
}
