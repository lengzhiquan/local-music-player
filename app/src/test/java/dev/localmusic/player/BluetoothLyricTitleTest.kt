package dev.localmusic.player

import dev.localmusic.player.lyrics.BluetoothLyricTitle
import dev.localmusic.player.lyrics.BluetoothTitles
import dev.localmusic.player.lyrics.LrcParser
import dev.localmusic.player.lyrics.LyricLine
import dev.localmusic.player.lyrics.Lyrics
import dev.localmusic.player.lyrics.bluetoothTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLyricTitleTest {
    private val song = "安静"
    private val artist = "周杰伦"
    private val album = "范特西"

    @Test
    fun shortLineIsShownWhole() {
        val lyrics = line(0L, "安静")
        assertEquals("安静", BluetoothLyricTitle.titleAt(lyrics, 0L))
        assertEquals("安静", BluetoothLyricTitle.titleAt(lyrics, 8_000L))
    }

    @Test
    fun lineThatFitsTheWindowDoesNotScroll() {
        val text = "一二三四五六七八九十一二"
        assertEquals(BluetoothLyricTitle.WINDOW_CHARS, text.length)
        val lyrics = line(1_000L, text)
        assertEquals(text, BluetoothLyricTitle.titleAt(lyrics, 1_000L))
        assertEquals(text, BluetoothLyricTitle.titleAt(lyrics, 9_000L))
    }

    @Test
    fun longLineSlidesAndHoldsTheTail() {
        val text = "0123456789ABCDEFGHIJ"
        val lyrics = line(0L, text)
        val window = 10
        assertEquals("0123456789", BluetoothLyricTitle.titleAt(lyrics, 0L, windowChars = window, minStepMs = 400, maxStepMs = 400))
        assertEquals("123456789A", BluetoothLyricTitle.titleAt(lyrics, 400L, windowChars = window, minStepMs = 400, maxStepMs = 400))
        assertEquals("123456789A", BluetoothLyricTitle.titleAt(lyrics, 799L, windowChars = window, minStepMs = 400, maxStepMs = 400))
        assertEquals("ABCDEFGHIJ", BluetoothLyricTitle.titleAt(lyrics, 4_000L, windowChars = window, minStepMs = 400, maxStepMs = 400))
        assertEquals("ABCDEFGHIJ", BluetoothLyricTitle.titleAt(lyrics, 20_000L, windowChars = window, minStepMs = 400, maxStepMs = 400))
    }

    @Test
    fun changingLineJumpsToTheNewLine() {
        val lyrics = Lyrics(
            synced = true,
            lines = listOf(
                LyricLine(0L, "AAAAAAAAAAAAAAA"),
                LyricLine(8_000L, "BBBBBBBBBBBBBBB"),
            ),
        )
        val late = BluetoothLyricTitle.titleAt(lyrics, 7_900L, windowChars = 5, minStepMs = 400, maxStepMs = 400)
        val jumped = BluetoothLyricTitle.titleAt(lyrics, 8_000L, windowChars = 5, minStepMs = 400, maxStepMs = 400)
        assertEquals("AAAAA", late)
        assertEquals("BBBBB", jumped)
    }

    @Test
    fun seekBackwardRewindsTheWindowAndPauseHoldsIt() {
        val text = "0123456789ABCDEFGHIJ"
        val lyrics = line(0L, text)
        val paused = BluetoothLyricTitle.titleAt(lyrics, 1_600L, windowChars = 10, minStepMs = 400, maxStepMs = 400)
        assertEquals(paused, BluetoothLyricTitle.titleAt(lyrics, 1_600L, windowChars = 10, minStepMs = 400, maxStepMs = 400))
        assertEquals("456789ABCD", paused)
        val rewound = BluetoothLyricTitle.titleAt(lyrics, 400L, windowChars = 10, minStepMs = 400, maxStepMs = 400)
        assertEquals("123456789A", rewound)
    }

    @Test
    fun missingOrUntimedLyricsFallBack() {
        assertNull(BluetoothLyricTitle.titleAt(null, 1_000L))
        assertNull(BluetoothLyricTitle.titleAt(Lyrics(true, emptyList()), 1_000L))
        assertNull(BluetoothLyricTitle.titleAt(Lyrics(false, listOf(LyricLine(null, "只有文字"))), 1_000L))
        assertNull(BluetoothLyricTitle.titleAt(line(5_000L, "还没开始"), 4_999L))
        assertNull(BluetoothLyricTitle.titleAt(line(0L, "   \n  "), 1_000L))
        assertNull(BluetoothLyricTitle.titleAt(LrcParser.parse("只有一段\n没有时间轴"), 1_000L))
    }

    @Test
    fun parsedLrcScrollsInTimeAndJumpsWithTheOffset() {
        val lyrics = LrcParser.parse(
            """
            [offset:100]
            [00:01.00]安静
            [00:05.00]我想就这样牵着你的手不放开
            [00:09.00]下一句
            """.trimIndent(),
        )
        assertEquals(listOf(1_100L, 5_100L, 9_100L), lyrics.lines.map { it.timeMs })
        assertNull(BluetoothLyricTitle.titleAt(lyrics, 1_000L))
        assertEquals("安静", BluetoothLyricTitle.titleAt(lyrics, 1_100L))
        assertEquals("安静", BluetoothLyricTitle.titleAt(lyrics, 5_099L))
        val longLine = "我想就这样牵着你的手不放开"
        assertEquals(13, longLine.length)
        assertEquals(longLine.take(12), BluetoothLyricTitle.titleAt(lyrics, 5_100L))
        // One character overflows a 12-wide window. The line lasts 4_000 ms, so the step clamps to 800 ms.
        assertEquals(800L, BluetoothLyricTitle.stepMs(4_000L, 1))
        assertEquals(longLine.drop(1), BluetoothLyricTitle.titleAt(lyrics, 5_900L))
        assertEquals("下一句", BluetoothLyricTitle.titleAt(lyrics, 9_100L))
    }

    @Test
    fun wordTimedLineStillScrollsTheWholeLine() {
        val lyrics = LrcParser.parse("[00:01.00]你<00:01.40>的<00:01.80>眼<00:02.20>神很安静地看着远方啊")
        val text = lyrics.lines.first().text
        assertEquals("你的眼神很安静地看着远方啊", text)
        assertTrue(text.length > BluetoothLyricTitle.WINDOW_CHARS)
        val shown = BluetoothLyricTitle.titleAt(lyrics, 1_000L)
        assertEquals(text.take(BluetoothLyricTitle.WINDOW_CHARS), shown)
        assertFalse(shown == "你")
    }

    @Test
    fun stepStaysInsideTheHeadUnitRange() {
        assertEquals(BluetoothLyricTitle.MIN_STEP_MS, BluetoothLyricTitle.stepMs(1_000L, 20))
        assertEquals(BluetoothLyricTitle.MAX_STEP_MS, BluetoothLyricTitle.stepMs(60_000L, 1))
        assertEquals(500L, BluetoothLyricTitle.stepMs(1_500L, 2))
    }

    @Test
    fun carTitleScrollsOnlyWhileEnabledConnectedAndActive() {
        val lyrics = line(0L, "我想就这样牵着你的手不放开")
        val scrolling = titles(lyrics, enabled = true, bluetooth = true, active = true, positionMs = 0L)
        assertEquals("我想就这样牵着你的手不放", scrolling.avrcpTitle)
        assertEquals(song, scrolling.phoneTitle)
        assertEquals(artist, scrolling.artist)
        assertEquals(album, scrolling.album)
        assertTrue(scrolling.scrolling)

        assertSong(titles(lyrics, enabled = false, bluetooth = true, active = true))
        assertSong(titles(lyrics, enabled = true, bluetooth = false, active = true))
        assertSong(titles(lyrics, enabled = true, bluetooth = true, active = false))
        assertSong(titles(null, enabled = true, bluetooth = true, active = true))
        assertSong(
            bluetoothTitles(
                songTitle = song,
                artist = artist,
                album = album,
                lyrics = lyrics,
                positionMs = 0L,
                enabled = true,
                bluetoothConnected = true,
                playbackActive = true,
                lyricMediaId = "9",
                currentMediaId = "8",
            ),
        )
    }

    @Test
    fun pauseKeepsTheWindowAndStopRestoresTheSong() {
        val lyrics = line(0L, "0123456789ABCDEFGHIJ")
        val paused = titles(
            lyrics,
            enabled = true,
            bluetooth = true,
            active = true,
            positionMs = 800L,
            windowChars = 10,
            minStepMs = 400,
            maxStepMs = 400,
        )
        assertEquals("23456789AB", paused.avrcpTitle)
        assertEquals(song, paused.phoneTitle)
        val stopped = titles(
            lyrics,
            enabled = true,
            bluetooth = true,
            active = false,
            positionMs = 800L,
            windowChars = 10,
            minStepMs = 400,
            maxStepMs = 400,
        )
        assertEquals(song, stopped.avrcpTitle)
        assertEquals(artist, stopped.artist)
        assertEquals(album, stopped.album)
        assertFalse(stopped.scrolling)
    }

    private fun line(timeMs: Long, text: String) = Lyrics(true, listOf(LyricLine(timeMs, text)))

    private fun titles(
        lyrics: Lyrics?,
        enabled: Boolean,
        bluetooth: Boolean,
        active: Boolean,
        positionMs: Long = 0L,
        mediaId: String = "7",
        windowChars: Int = BluetoothLyricTitle.WINDOW_CHARS,
        minStepMs: Long = BluetoothLyricTitle.MIN_STEP_MS,
        maxStepMs: Long = BluetoothLyricTitle.MAX_STEP_MS,
    ): BluetoothTitles {
        return bluetoothTitles(
            songTitle = song,
            artist = artist,
            album = album,
            lyrics = lyrics,
            positionMs = positionMs,
            enabled = enabled,
            bluetoothConnected = bluetooth,
            playbackActive = active,
            lyricMediaId = mediaId,
            currentMediaId = mediaId,
            windowChars = windowChars,
            minStepMs = minStepMs,
            maxStepMs = maxStepMs,
        )
    }

    private fun assertSong(titles: BluetoothTitles) {
        assertEquals(song, titles.avrcpTitle)
        assertEquals(song, titles.phoneTitle)
        assertEquals(artist, titles.artist)
        assertEquals(album, titles.album)
        assertFalse(titles.scrolling)
    }
}
