package dev.localmusic.player.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ChannelSource(private val channel: FileChannel) : RandomRead {
    override val size: Long = runCatching { channel.size() }.getOrDefault(0L)

    override fun read(pos: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || pos < 0 || pos >= size) return -1
        val buffer = ByteBuffer.wrap(dst, offset, length)
        var got = 0
        var cursor = pos
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, cursor)
            if (n < 0) break
            if (n == 0) break
            got += n
            cursor += n
        }
        return if (got == 0) -1 else got
    }
}

enum class LyricOrigin { EMBEDDED, SIDECAR, CACHE }

data class LocalLyricLookup(
    val lyrics: Lyrics?,
    val origin: LyricOrigin?,
    val cachedMiss: Boolean,
)

object LyricsLoader {
    fun load(context: Context, audioUri: Uri, treeUri: String?, fileName: String): Lyrics? {
        return loadLocal(context, audioUri, treeUri, fileName, audioUri.toString()).lyrics
    }

    fun loadLocal(
        context: Context,
        audioUri: Uri,
        treeUri: String?,
        fileName: String,
        trackUri: String,
    ): LocalLyricLookup {
        val embedded = runCatching { readEmbedded(context, audioUri) }.getOrNull()
        val sidecar = if (embedded != null && embedded.hasText()) {
            null
        } else {
            runCatching { readSidecar(context, audioUri, treeUri, fileName) }.getOrNull()
        }
        if (embedded != null && embedded.hasText()) {
            return LocalLyricLookup(embedded, LyricOrigin.EMBEDDED, false)
        }
        if (sidecar != null && sidecar.hasText()) {
            return LocalLyricLookup(sidecar, LyricOrigin.SIDECAR, false)
        }
        return when (val cached = LyricsCache.read(context, trackUri)) {
            is LyricsCache.Entry.Found -> {
                val parsed = LrcParser.parse(LrcParser.decodeBytes(cached.raw.toByteArray()))
                if (parsed.hasText()) LocalLyricLookup(parsed, LyricOrigin.CACHE, false)
                else LocalLyricLookup(null, null, false)
            }
            LyricsCache.Entry.Miss -> LocalLyricLookup(null, null, true)
            LyricsCache.Entry.Absent -> LocalLyricLookup(null, null, false)
        }
    }

    private fun readEmbedded(context: Context, uri: Uri): Lyrics? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return try {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            EmbeddedLyricsReader.read(ChannelSource(channel))
        } finally {
            pfd.close()
        }
    }

    private fun readSidecar(context: Context, audioUri: Uri, treeUri: String?, fileName: String): Lyrics? {
        val lrcUri = findSidecar(context, audioUri, treeUri, fileName) ?: return null
        val bytes = context.contentResolver.openInputStream(lrcUri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > 1_000_000) return null
        return LrcParser.parse(LrcParser.decodeBytes(bytes))
    }

    fun findSidecar(context: Context, audioUri: Uri, treeUri: String?, fileName: String): Uri? {
        val base = fileName.substringBeforeLast('.', fileName)
        val names = listOf("$base.lrc", "$base.LRC", "$base.Lrc")
        val docId = runCatching { DocumentsContract.getDocumentId(audioUri) }.getOrNull()
        if (!treeUri.isNullOrBlank() && docId != null) {
            val tree = Uri.parse(treeUri)
            val prefix = parentPrefix(docId) ?: return null
            for (name in names) {
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, prefix + name)
                if (canRead(context, child)) return child
            }
        }
        if (docId != null && audioUri.authority != null) {
            val prefix = parentPrefix(docId) ?: return null
            for (name in names) {
                val child = DocumentsContract.buildDocumentUri(audioUri.authority!!, prefix + name)
                if (canRead(context, child)) return child
            }
        }
        return null
    }

    private fun parentPrefix(docId: String): String? {
        val slash = docId.lastIndexOf('/')
        if (slash > 0) return docId.substring(0, slash + 1)
        val colon = docId.lastIndexOf(':')
        if (colon >= 0 && colon < docId.length - 1) return docId.substring(0, colon + 1)
        return null
    }

    private fun canRead(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
