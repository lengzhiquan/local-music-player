package dev.localmusic.player.lyrics

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

object LrcParser {
    private val timeRegex = Regex("""\[\s*(\d{1,3})\s*:\s*(\d{1,2})\s*(?:[:.]\s*(\d{1,3}))?\s*]""")
    private val parenTimeRegex = Regex("""\(\s*(\d{1,3})\s*:\s*(\d{1,2})\s*(?:[:.]\s*(\d{1,3}))?\s*\)""")
    private val offsetRegex = Regex("""\[\s*offset\s*:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.toString(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.toString(Charsets.UTF_16BE)
        }
        val payload = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            runCatching { payload.toString(Charset.forName("GB18030")) }
                .getOrElse { payload.toString(Charsets.ISO_8859_1) }
        }
    }

    fun parse(text: String): Lyrics {
        val normalized = normalize(text)
        val offset = offsetRegex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val syncedLines = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        for (raw in normalized.split('\n')) {
            val line = raw.trim().trim('\uFEFF')
            if (line.isEmpty()) continue
            val bracketTimes = timeRegex.findAll(line).toList()
            val times = bracketTimes.ifEmpty { parenTimeRegex.findAll(line).toList() }
            val pattern = if (bracketTimes.isNotEmpty()) timeRegex else parenTimeRegex
            if (times.isEmpty()) {
                if (!line.startsWith("[") || !line.endsWith("]")) plain += line
                continue
            }
            syncedLines += segments(line, times, pattern, offset)
        }
        val kept = syncedLines.filter { it.text.isNotBlank() }
        if (kept.isNotEmpty()) {
            return Lyrics(synced = true, lines = kept.sortedBy { it.timeMs ?: 0L })
        }
        if (plain.isEmpty()) return Lyrics(synced = false, lines = emptyList())
        return Lyrics(synced = false, lines = plain.map { LyricLine(null, it) })
    }

    /** Fullwidth punctuation and digits, BOMs, and escaped newlines from embedded tags. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\uFEFF' -> Unit
                '［', '【' -> sb.append('[')
                '］', '】' -> sb.append(']')
                '：' -> sb.append(':')
                '．' -> sb.append('.')
                in '０'..'９' -> sb.append('0' + (ch - '０'))
                else -> sb.append(ch)
            }
        }
        var text = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
        if (text.contains("\\n") && text.count { it == '\n' } < 2) {
            text = text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
        }
        return text
    }

    /**
     * Adjacent timestamps with nothing between them share the following text
     * (`[00:01.00][00:15.00]副歌`). Timestamps with text between them are separate lines.
     */
    private fun segments(
        line: String,
        times: List<MatchResult>,
        pattern: Regex,
        offset: Long,
    ): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var index = 0
        while (index < times.size) {
            val group = mutableListOf(times[index])
            var last = index
            while (last + 1 < times.size) {
                val between = line.substring(times[last].range.last + 1, times[last + 1].range.first)
                if (between.isBlank()) {
                    group += times[last + 1]
                    last++
                } else {
                    break
                }
            }
            val contentStart = times[last].range.last + 1
            val contentEnd = if (last + 1 < times.size) times[last + 1].range.first else line.length
            val content = pattern.replace(line.substring(contentStart, contentEnd), "").trim()
            if (content.isNotEmpty()) {
                for (stamp in group) {
                    val time = stampMillis(stamp.groupValues) + offset
                    val words = wordsOf(time, content, offset)
                    val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else wordRegex.replace(content, "")
                    if (text.isNotBlank()) out += LyricLine(time, text, words)
                }
            }
            index = last + 1
        }
        return out
    }

    private val wordRegex = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** `<mm:ss.xx>` starts the syllable that follows it. Text before the first tag uses the line time. */
    private fun wordsOf(lineTime: Long, content: String, offset: Long): List<LyricWord> {
        if (!wordRegex.containsMatchIn(content)) return emptyList()
        val matches = wordRegex.findAll(content).toList()
        val words = mutableListOf<LyricWord>()
        val lead = content.substring(0, matches.first().range.first)
        if (lead.isNotEmpty()) words += LyricWord(lineTime, lead)
        for (i in matches.indices) {
            val time = stampMillis(matches[i].groupValues) + offset
            val from = matches[i].range.last + 1
            val to = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val text = content.substring(from, to)
            if (text.isNotEmpty()) words += LyricWord(time, text)
        }
        if (words.isEmpty()) return emptyList()
        return if (kotlin.math.abs(words.first().timeMs - lineTime) <= 1_500L) words else emptyList()
    }

    private fun stampMillis(groups: List<String>): Long {
        val minutes = groups.getOrNull(1)?.toLongOrNull() ?: return 0L
        val seconds = groups.getOrNull(2)?.toLongOrNull() ?: return 0L
        val fraction = groups.getOrNull(3).orEmpty()
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100
            fraction.length == 2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }
}
