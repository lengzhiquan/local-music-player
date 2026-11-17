package dev.localmusic.player.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

data class ScannedTrack(
    val uri: Uri,
    val treeUri: Uri?,
    val tags: AudioTags,
)

object FolderScanner {
    private val extensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")

    fun scanTree(context: Context, treeUri: Uri, onFound: (Int) -> Unit = {}): List<ScannedTrack> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val found = ArrayList<ScannedTrack>()
        walk(context, root, treeUri, found, onFound)
        return found.sortedBy { it.tags.title.lowercase() }
    }

    fun scanFiles(context: Context, uris: List<Uri>): List<ScannedTrack> {
        return uris.mapNotNull { uri ->
            val name = AudioMetadata.displayName(context, uri) ?: uri.lastPathSegment ?: return@mapNotNull null
            if (!isAudioName(name)) return@mapNotNull null
            val size = lengthOf(context, uri)
            val tags = runCatching { AudioMetadata.read(context, uri, name, null, size) }.getOrNull()
                ?: AudioTags(name.substringBeforeLast('.'), "", "", 0L, AudioMetadata.guessMime(name), null, null, name)
            ScannedTrack(uri, null, tags)
        }
    }

    private fun walk(
        context: Context,
        dir: DocumentFile,
        treeUri: Uri,
        out: MutableList<ScannedTrack>,
        onFound: (Int) -> Unit,
    ) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                walk(context, child, treeUri, out, onFound)
            } else if (isAudioName(name) || child.type?.startsWith("audio/") == true && isAudioName(name)) {
                val size = runCatching { child.length() }.getOrDefault(0L)
                val tags = runCatching {
                    AudioMetadata.read(context, child.uri, name, child.type, size)
                }.getOrElse {
                    AudioTags(name.substringBeforeLast('.'), "", "", 0L, AudioMetadata.guessMime(name), null, null, name)
                }
                out += ScannedTrack(child.uri, treeUri, tags)
                onFound(out.size)
            }
        }
    }

    private fun isAudioName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in extensions
    }

    private fun lengthOf(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
