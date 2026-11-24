package dev.localmusic.player.lyrics

/**
 * QQ Music-style lyric title for a Bluetooth head unit.
 *
 * Car stereos only show AVRCP metadata. The phone puts a sliding window of the
 * current lyric line into the title field, so the head unit appears to scroll.
 * Short lines are sent whole. A long line advances one character at a time and
 * holds the tail until the next line, which always starts at the first character.
 *
 * [BluetoothTitles.avrcpTitle] is the string for METADATA_KEY_TITLE. Android's
 * Bluetooth stack sends that key to the car. [BluetoothTitles.phoneTitle] is the
 * real song name, kept for METADATA_KEY_DISPLAY_TITLE so the notification and
 * lock screen can stay on the song. Artist and album are never rewritten.
 */
object BluetoothLyricTitle {
    /** Characters that fit in a typical head-unit title before it truncates. */
    const val WINDOW_CHARS = 12

    /** Faster than this and some stereos drop metadata updates. */
    const val MIN_STEP_MS = 350L

    /** Slower than this and a long line barely moves. */
    const val MAX_STEP_MS = 800L

    /**
     * How long to wait before revealing the next character.
     * The step is paced to the line when that stays inside [minStepMs]..[maxStepMs].
     */
    fun stepMs(
        lineDurationMs: Long,
        overflow: Int,
        minStepMs: Long = MIN_STEP_MS,
        maxStepMs: Long = MAX_STEP_MS,
    ): Long {
        val min = minOf(minStepMs, maxStepMs)
        val max = maxOf(minStepMs, maxStepMs)
        if (overflow <= 0 || lineDurationMs <= 0L) return max
        val ideal = lineDurationMs / (overflow + 1L)
        return ideal.coerceIn(min, max)
    }

    /**
     * Lyric text for [positionMs], or null when the song title should be used.
     * Null covers missing lyrics, unsynced text, and the gap before the first stamp.
     */
    fun titleAt(
        lyrics: Lyrics?,
        positionMs: Long,
        windowChars: Int = WINDOW_CHARS,
        minStepMs: Long = MIN_STEP_MS,
        maxStepMs: Long = MAX_STEP_MS,
    ): String? {
        if (lyrics == null || !lyrics.synced) return null
        val index = lyrics.currentIndex(positionMs)
        if (index < 0) return null
        val line = lyrics.lines[index]
        val text = oneLine(line.text)
        if (text.isEmpty()) return null
        val window = windowChars.coerceAtLeast(1)
        if (text.length <= window) return text
        val start = line.timeMs ?: return text.substring(0, window)
        val next = lyrics.lines.asSequence().drop(index + 1).mapNotNull { it.timeMs }.firstOrNull()
        val duration = if (next != null && next > start) next - start else Long.MAX_VALUE / 4
        val overflow = text.length - window
        val step = stepMs(duration, overflow, minStepMs, maxStepMs).coerceAtLeast(1L)
        val elapsed = (positionMs - start).coerceAtLeast(0L)
        val offset = (elapsed / step).toInt().coerceIn(0, overflow)
        return text.substring(offset, offset + window)
    }

    private fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ').trim()
}

data class BluetoothTitles(
    val avrcpTitle: String,
    val phoneTitle: String,
    val artist: String,
    val album: String,
) {
    /** True when the car title is a lyric window rather than the song name. */
    val scrolling: Boolean get() = avrcpTitle != phoneTitle
}

/**
 * Metadata to publish for the current moment.
 * The car title scrolls only while the feature is on, a Bluetooth output is
 * connected, playback has not stopped, and the lyric belongs to this track.
 * Pause stays on the window for the paused position. Stop, skip to a song
 * without timed lyrics, or turning the feature off restores the song title.
 */
fun bluetoothTitles(
    songTitle: String,
    artist: String,
    album: String,
    lyrics: Lyrics?,
    positionMs: Long,
    enabled: Boolean,
    bluetoothConnected: Boolean,
    playbackActive: Boolean,
    lyricMediaId: String?,
    currentMediaId: String?,
    windowChars: Int = BluetoothLyricTitle.WINDOW_CHARS,
    minStepMs: Long = BluetoothLyricTitle.MIN_STEP_MS,
    maxStepMs: Long = BluetoothLyricTitle.MAX_STEP_MS,
): BluetoothTitles {
    val lyric = if (enabled && bluetoothConnected && playbackActive &&
        currentMediaId != null && currentMediaId == lyricMediaId
    ) {
        BluetoothLyricTitle.titleAt(lyrics, positionMs, windowChars, minStepMs, maxStepMs)
    } else {
        null
    }
    return BluetoothTitles(
        avrcpTitle = lyric ?: songTitle,
        phoneTitle = songTitle,
        artist = artist,
        album = album,
    )
}
