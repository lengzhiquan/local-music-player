package dev.localmusic.player.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

data class AudioTags(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val bitrateBps: Int?,
    val sampleRate: Int?,
    val fileName: String,
)

object AudioMetadata {
    fun read(context: Context, uri: Uri, fileName: String, mimeHint: String?, sizeBytes: Long): AudioTags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var mime = mimeHint

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!trackMime.startsWith("audio/")) continue
                mime = trackMime
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (bitrate == null && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
                if (durationMs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
                break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        if (durationMs > 0 && sizeBytes > 0) {
            val fromSize = ((sizeBytes * 8_000L) / durationMs).toInt()
            if (bitrate == null || bitrate <= 0) {
                bitrate = fromSize
            } else if (fromSize > 8_000 && (bitrate > fromSize * 3 || bitrate * 3 < fromSize)) {
                // Some tags report a nominal bitrate that does not match the file.
                bitrate = fromSize
            }
        }

        val baseTitle = fileName.substringBeforeLast('.', fileName)
        return AudioTags(
            title = title?.trim().takeUnless { it.isNullOrEmpty() } ?: baseTitle,
            artist = artist?.trim().orEmpty(),
            album = album?.trim().orEmpty(),
            durationMs = durationMs.coerceAtLeast(0L),
            mime = mime?.ifBlank { guessMime(fileName) } ?: guessMime(fileName),
            bitrateBps = bitrate?.takeIf { it > 0 },
            sampleRate = sampleRate?.takeIf { it > 0 },
            fileName = fileName,
        )
    }

    fun displayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    fun guessMime(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
    }

    fun formatLabel(mime: String, fileName: String): String {
        // The filename extension is the format the user saved. Some platform
        // extractors report FLAC or OGG as audio/raw, which would hide it.
        val fromName = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "MP3"
            "flac" -> "FLAC"
            "m4a" -> "M4A"
            "aac" -> "AAC"
            "ogg", "oga" -> "OGG"
            "opus" -> "OPUS"
            "wav" -> "WAV"
            else -> null
        }
        if (fromName != null) return fromName
        return when {
            mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
            mime.contains("flac") -> "FLAC"
            mime.contains("mp4") || mime.contains("aac") || mime.contains("alac") -> "M4A"
            mime.contains("opus") -> "OPUS"
            mime.contains("ogg") || mime.contains("vorbis") -> "OGG"
            mime.contains("wav") || mime.contains("raw") -> "WAV"
            else -> "音频"
        }
    }

    fun techLine(mime: String, fileName: String, bitrateBps: Int?, sampleRate: Int?): String {
        val parts = mutableListOf(formatLabel(mime, fileName))
        if (bitrateBps != null && bitrateBps > 0) {
            parts += "${bitrateBps / 1000} kbps"
        }
        if (sampleRate != null && sampleRate > 0) {
            parts += if (sampleRate % 100 == 0) {
                val khz = sampleRate / 1000.0
                if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format("%.1f kHz", khz)
            } else {
                "$sampleRate Hz"
            }
        }
        return parts.joinToString(" · ")
    }
}
