package dev.localmusic.player.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public LRCLIB lookup. No key. Called only after local lyrics miss, and only when the user asks or opts in. */
object LrcLibClient {
    const val USER_AGENT = "LocalMusic/1.1 (dev.localmusic.player; lyrics via lrclib.net)"

    fun fetch(title: String, artist: String, album: String, durationMs: Long): String? {
        val seconds = (durationMs / 1000L).toInt().coerceAtLeast(0)
        val direct = http(buildGet(title, artist, album, seconds))
        if (direct != null) {
            val text = LrcLibParser.parseBody(direct).firstOrNull()?.takeIf { !it.instrumental }?.bestText()
            if (!text.isNullOrBlank()) return text
        }
        val found = http(buildSearch(title, artist)) ?: return null
        return LrcLibParser.chooseHit(LrcLibParser.parseBody(found), seconds)?.bestText()?.ifBlank { null }
    }

    fun buildGet(title: String, artist: String, album: String, durationSec: Int): String {
        val parts = mutableListOf("track_name" to title)
        if (artist.isNotBlank()) parts += "artist_name" to artist
        if (album.isNotBlank()) parts += "album_name" to album
        if (durationSec > 0) parts += "duration" to durationSec.toString()
        return "https://lrclib.net/api/get?" + form(parts)
    }

    fun buildSearch(title: String, artist: String): String {
        val parts = mutableListOf("track_name" to title)
        if (artist.isNotBlank()) parts += "artist_name" to artist
        return "https://lrclib.net/api/search?" + form(parts)
    }

    private fun form(parts: List<Pair<String, String>>): String {
        return parts.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
    }

    private fun http(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        return try {
            when (val code = connection.responseCode) {
                404 -> null
                in 200..299 -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                else -> error("LRCLIB $code")
            }
        } finally {
            connection.disconnect()
        }
    }
}
