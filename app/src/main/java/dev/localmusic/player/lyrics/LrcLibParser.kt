package dev.localmusic.player.lyrics

data class LrcHit(
    val trackName: String,
    val artistName: String,
    val durationSec: Int,
    val instrumental: Boolean,
    val plain: String,
    val synced: String,
) {
    fun bestText(): String = synced.ifBlank { plain }
}

object LrcLibParser {
    fun parseBody(body: String): List<LrcHit> {
        val value = JsonReader(body).parse()
        val objects = when (value) {
            is Map<*, *> -> listOf(value)
            is List<*> -> value.mapNotNull { it as? Map<*, *> }
            else -> emptyList()
        }
        return objects.map { obj ->
            LrcHit(
                trackName = obj.string("trackName"),
                artistName = obj.string("artistName"),
                durationSec = obj.int("duration"),
                instrumental = obj["instrumental"] == true,
                plain = obj.string("plainLyrics"),
                synced = obj.string("syncedLyrics"),
            )
        }
    }

    /** Prefer a duration close to the file. Ignore hits with no lyric text. */
    fun chooseHit(hits: List<LrcHit>, durationSec: Int): LrcHit? {
        val usable = hits.filter { !it.instrumental && it.bestText().isNotBlank() }
        if (usable.isEmpty()) return null
        if (durationSec <= 0) return usable.first()
        val ranked = usable.sortedBy { kotlin.math.abs(it.durationSec - durationSec) }
        val closest = ranked.first()
        val delta = kotlin.math.abs(closest.durationSec - durationSec)
        if (closest.durationSec == 0 || delta <= 12) return closest
        return null
    }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String).orEmpty()

    private fun Map<*, *>.int(key: String): Int = when (val value = this[key]) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        else -> 0
    }
}

private class JsonReader(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        skip()
        if (index >= text.length) return null
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skip()
        if (peek('}')) {
            index++
            return map
        }
        while (index < text.length) {
            skip()
            val key = parseString()
            skip()
            expect(':')
            map[key] = parse()
            skip()
            if (peek('}')) {
                index++
                return map
            }
            expect(',')
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = mutableListOf<Any?>()
        skip()
        if (peek(']')) {
            index++
            return list
        }
        while (index < text.length) {
            list += parse()
            skip()
            if (peek(']')) {
                index++
                return list
            }
            expect(',')
        }
        return list
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) break
                    when (val esc = text[index++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(index, (index + 4).coerceAtMost(text.length))
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> out.append(esc)
                    }
                }
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun parseNumber(): Any {
        val start = index
        if (peek('-')) index++
        while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
        val raw = text.substring(start, index)
        return if ('.' in raw) raw.toDouble() else raw.toLong()
    }

    private fun literal(token: String, value: Any?): Any? {
        index += token.length
        return value
    }

    private fun expect(char: Char) {
        skip()
        if (index >= text.length || text[index] != char) error("expected $char")
        index++
    }

    private fun peek(char: Char): Boolean = index < text.length && text[index] == char

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
