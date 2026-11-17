package dev.localmusic.player.library

import dev.localmusic.player.data.TrackEntity
import java.text.Collator
import java.util.Locale

enum class TrackSort {
    PLAYLIST,
    TITLE,
    ARTIST,
    ;

    val label: String
        get() = when (this) {
            PLAYLIST -> "默认顺序"
            TITLE -> "按歌名"
            ARTIST -> "按歌手"
        }
}

/**
 * Playlist ordering and search.
 * Chinese uses the China collation (pinyin). Latin is case-insensitive.
 * Digit runs compare as numbers, so 歌2 comes before 歌10.
 * A missing title falls back to the file name. A missing artist sorts after named artists.
 */
object TrackOrder {
    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    fun arrange(
        tracks: List<TrackEntity>,
        sort: TrackSort,
        query: String,
        latinOf: (String) -> String = ::deviceLatin,
    ): List<TrackEntity> {
        return sorted(tracks.filter { matches(it, query, latinOf) }, sort)
    }

    fun sorted(tracks: List<TrackEntity>, sort: TrackSort): List<TrackEntity> {
        return when (sort) {
            TrackSort.PLAYLIST -> tracks.sortedWith(compareBy<TrackEntity> { it.position }.thenBy { it.id })
            TrackSort.TITLE -> tracks.sortedWith { a, b ->
                val byTitle = compareText(titleOf(a), titleOf(b))
                if (byTitle != 0) byTitle else a.id.compareTo(b.id)
            }
            TrackSort.ARTIST -> tracks.sortedWith { a, b ->
                val byArtist = compareText(a.artist.trim(), b.artist.trim())
                if (byArtist != 0) return@sortedWith byArtist
                val byTitle = compareText(titleOf(a), titleOf(b))
                if (byTitle != 0) byTitle else a.id.compareTo(b.id)
            }
        }
    }

    fun matches(track: TrackEntity, query: String, latinOf: (String) -> String = ::deviceLatin): Boolean {
        val raw = query.trim()
        if (raw.isEmpty()) return true
        if (track.title.contains(raw, ignoreCase = true) ||
            track.artist.contains(raw, ignoreCase = true) ||
            track.fileName.contains(raw, ignoreCase = true)
        ) {
            return true
        }
        val needle = compact(latinOf(raw))
        if (needle.isEmpty()) return false
        val haystack = compact(
            listOf(track.title, track.artist, track.fileName).joinToString(" ") { latinOf(it) },
        )
        return haystack.contains(needle)
    }

    private fun titleOf(track: TrackEntity): String {
        return track.title.trim().ifEmpty { track.fileName.substringBeforeLast('.', track.fileName).trim() }
    }

    private fun compareText(a: String, b: String): Int {
        val missingA = a.isBlank()
        val missingB = b.isBlank()
        if (missingA != missingB) return if (missingA) 1 else -1
        if (missingA) return 0
        return compareNatural(a, b)
    }

    private fun compareNatural(a: String, b: String): Int {
        val left = tokens(a)
        val right = tokens(b)
        val count = minOf(left.size, right.size)
        for (index in 0 until count) {
            val compared = compareToken(left[index], right[index])
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private fun compareToken(a: Token, b: Token): Int {
        return when {
            a is Token.Number && b is Token.Number -> a.value.compareTo(b.value)
            a is Token.Number -> -1
            b is Token.Number -> 1
            else -> collator.compare((a as Token.Text).value, (b as Token.Text).value)
        }
    }

    private fun tokens(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var index = 0
        while (index < text.length) {
            if (text[index].isDigit()) {
                val start = index
                while (index < text.length && text[index].isDigit()) index++
                out += Token.Number(text.substring(start, index).toLongOrNull() ?: Long.MAX_VALUE)
            } else {
                val start = index
                while (index < text.length && !text[index].isDigit()) index++
                out += Token.Text(text.substring(start, index))
            }
        }
        return out
    }

    private sealed interface Token {
        data class Text(val value: String) : Token
        data class Number(val value: Long) : Token
    }

    /** Han to Latin on Android. Unit tests can pass their own [latinOf] and never call this. */
    fun deviceLatin(text: String): String {
        return try {
            val type = Class.forName("android.icu.text.Transliterator")
            val instance = type.getMethod("getInstance", String::class.java)
                .invoke(null, "Han-Latin; Latin-ASCII")
            type.getMethod("transliterate", String::class.java).invoke(instance, text) as String
        } catch (_: Throwable) {
            text
        }
    }

    fun compact(text: String): String {
        return text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }
}
