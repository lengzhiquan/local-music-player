package dev.localmusic.player.lyrics

data class LyricWord(
    val timeMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val words: List<LyricWord> = emptyList(),
) {
    fun hasWordTiming(): Boolean = words.size >= 2

    /**
     * How many characters of this line have been reached.
     * Word-timed lines fill character by character between stamps.
     * Line-timed lines switch the whole line at once.
     */
    fun sungCharacters(positionMs: Long): Int {
        if (!hasWordTiming()) {
            return if (timeMs != null && positionMs >= timeMs) text.length else 0
        }
        var count = 0
        for (i in words.indices) {
            val start = words[i].timeMs
            val end = words.getOrNull(i + 1)?.timeMs
                ?: (start + words[i].text.length.coerceAtLeast(1) * 180L)
            if (positionMs >= end) {
                count += words[i].text.length
            } else if (positionMs > start) {
                val span = (end - start).coerceAtLeast(1L)
                val fraction = (positionMs - start).toFloat() / span.toFloat()
                count += (words[i].text.length * fraction).toInt().coerceIn(0, words[i].text.length)
                break
            } else {
                break
            }
        }
        return count.coerceIn(0, text.length)
    }
}

data class Lyrics(
    val synced: Boolean,
    val lines: List<LyricLine>,
) {
    fun hasText(): Boolean = lines.any { it.text.isNotBlank() }

    fun plainText(): String = lines.joinToString("\n") { it.text }.trim()

    /** Index of the line that should be highlighted, or -1 before the first timestamp. */
    fun currentIndex(positionMs: Long): Int {
        if (!synced) return -1
        var index = -1
        for (i in lines.indices) {
            val time = lines[i].timeMs ?: continue
            if (time <= positionMs) index = i else break
        }
        return index
    }

    /** Line to center when the text has no timestamps: progress through the song. */
    fun plainFocusIndex(positionMs: Long, durationMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (durationMs <= 0L) return 0
        val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 0.999f)
        return (fraction * lines.size).toInt().coerceIn(0, lines.lastIndex)
    }
}

/**
 * Y translation that places [activeIndex] in the vertical center.
 * Previous lines use [linePx]; the active line uses [activePx].
 */
fun lyricTranslationY(
    activeIndex: Int,
    viewportPx: Float,
    linePx: Float,
    activePx: Float,
): Float {
    val index = activeIndex.coerceAtLeast(0)
    val before = index * linePx
    val current = if (activeIndex >= 0) activePx else linePx
    return viewportPx / 2f - before - current / 2f
}

/**
 * Turns syllable-sized sync events into display lines.
 * Events close together stay on one line and keep their own timestamps.
 * A long gap, punctuation, or an already-long phrase starts a new line.
 */
fun linesFromSyncEvents(events: List<LyricLine>): List<LyricLine> {
    val sorted = events.sortedBy { it.timeMs ?: Long.MAX_VALUE }
    if (sorted.isEmpty()) return emptyList()
    val out = mutableListOf<LyricLine>()
    val bucket = mutableListOf<LyricWord>()
    fun flush() {
        if (bucket.isEmpty()) return
        out += LyricLine(
            timeMs = bucket.first().timeMs,
            text = bucket.joinToString("") { it.text },
            words = if (bucket.size >= 2) bucket.toList() else emptyList(),
        )
        bucket.clear()
    }
    for (event in sorted) {
        val time = event.timeMs ?: continue
        val raw = event.text
        if (raw.isBlank()) continue
        if (raw.length > 10 || raw.contains('\n')) {
            flush()
            raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                out += LyricLine(time, part)
            }
            continue
        }
        if (bucket.isNotEmpty() && time - bucket.last().timeMs > 650L) flush()
        bucket += LyricWord(time, raw)
        val last = raw.last()
        if (last in "。！？!?；;" || bucket.sumOf { it.text.length } >= 16) flush()
    }
    flush()
    return out.ifEmpty { sorted }
}

/**
 * Embedded lyrics win, then a same-name sidecar, then a previously fetched cache.
 * Null means nothing local to show. Online lookup is a separate, optional step.
 */
fun chooseLyrics(embedded: Lyrics?, sidecar: Lyrics?, cached: Lyrics? = null): Lyrics? {
    if (embedded != null && embedded.hasText()) return embedded
    if (sidecar != null && sidecar.hasText()) return sidecar
    if (cached != null && cached.hasText()) return cached
    return null
}
