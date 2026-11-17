package dev.localmusic.player.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.localmusic.player.data.TrackEntity
import java.io.File
import java.security.MessageDigest

/** Offline copy of a previous LRCLIB result, plus a best-effort write beside the audio file. */
object LyricsCache {
    sealed class Entry {
        data class Found(val raw: String) : Entry()
        data object Miss : Entry()
        data object Absent : Entry()
    }

    fun read(context: Context, uri: String): Entry {
        val file = fileFor(context, uri)
        if (!file.exists()) return Entry.Absent
        val text = runCatching { file.readText() }.getOrNull() ?: return Entry.Absent
        val lines = text.split('\n', limit = 2)
        return when (lines.firstOrNull()) {
            "miss" -> Entry.Miss
            "found" -> {
                val raw = lines.getOrNull(1).orEmpty()
                if (raw.isBlank()) Entry.Miss else Entry.Found(raw)
            }
            else -> Entry.Absent
        }
    }

    fun saveFound(context: Context, track: TrackEntity, raw: String) {
        val file = fileFor(context, track.uri)
        file.parentFile?.mkdirs()
        file.writeText("found\n$raw")
        runCatching { writeBeside(context, track, raw) }
    }

    fun saveMiss(context: Context, uri: String) {
        val file = fileFor(context, uri)
        file.parentFile?.mkdirs()
        file.writeText("miss\n")
    }

    private fun writeBeside(context: Context, track: TrackEntity, raw: String) {
        val tree = track.treeUri ?: return
        val audioUri = Uri.parse(track.uri)
        val docId = runCatching { DocumentsContract.getDocumentId(audioUri) }.getOrNull() ?: return
        if (!docId.contains("/")) return
        val parentId = docId.substringBeforeLast("/")
        val parent = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), parentId)
        val base = track.fileName.substringBeforeLast('.', track.fileName)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "application/octet-stream",
            "$base.lrc",
        ) ?: return
        context.contentResolver.openOutputStream(created)?.use { stream ->
            stream.write(raw.toByteArray(Charsets.UTF_8))
        }
    }

    private fun fileFor(context: Context, uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(context.filesDir, "lyrics/$name.cache")
    }
}
