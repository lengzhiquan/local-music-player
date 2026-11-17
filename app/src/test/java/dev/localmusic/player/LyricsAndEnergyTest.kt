package dev.localmusic.player

import dev.localmusic.player.energy.EnergyGroup
import dev.localmusic.player.energy.EnergyMath
import dev.localmusic.player.energy.PcmSampler
import dev.localmusic.player.energy.WavPcm
import dev.localmusic.player.lyrics.ByteSource
import dev.localmusic.player.lyrics.EmbeddedLyricsReader
import dev.localmusic.player.lyrics.LrcParser
import dev.localmusic.player.lyrics.LyricLine
import dev.localmusic.player.lyrics.Lyrics
import dev.localmusic.player.lyrics.chooseLyrics
import dev.localmusic.player.lyrics.lyricTranslationY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.sin
import kotlin.random.Random

class LyricsAndEnergyTest {
    @Test
    fun lrcTimestampsAndOffset() {
        val lyrics = LrcParser.parse(
            """
            [ti:夜]
            [offset:100]
            [00:01.00]第一行
            [00:01.50][00:03.00]重复
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_100L, 1_600L, 3_100L), lyrics.lines.map { it.timeMs })
        assertEquals("第一行", lyrics.lines[0].text)
        assertEquals(0, lyrics.currentIndex(1_500))
        assertEquals(1, lyrics.currentIndex(1_600))
        assertEquals(2, lyrics.currentIndex(3_200))
        assertEquals(-1, lyrics.currentIndex(0))
    }

    @Test
    fun enhancedLrcFillsCharactersAsTimeAdvances() {
        val lyrics = LrcParser.parse("[00:01.00]你<00:01.40>的<00:01.80>眼<00:02.20>神\n[00:04.00]下一句")
        assertTrue(lyrics.synced)
        val line = lyrics.lines.first()
        assertEquals(listOf(1000L, 1400L, 1800L, 2200L), line.words.map { it.timeMs })
        assertEquals("你的眼神", line.text)
        assertEquals(-1, lyrics.currentIndex(900))
        assertEquals(0, line.sungCharacters(1000))
        assertTrue(line.sungCharacters(1600) in 1..2)
        assertEquals(4, line.sungCharacters(2600))
        assertEquals(1, lyrics.currentIndex(4000))
        assertEquals("下一句", lyrics.lines[1].text)
        assertTrue(!lyrics.lines[1].hasWordTiming())
    }

    @Test
    fun realWorldLrcFormatsStaySynced() {
        val traditional = LrcParser.parse(
            "\uFEFF［００：１１．００］像一陣細雨\r\n［００：１６：５０］灑落我心底\r\n[00:22]那感覺如此神秘\r\n",
        )
        assertTrue(traditional.synced)
        assertEquals(listOf("像一陣細雨", "灑落我心底", "那感覺如此神秘"), traditional.lines.map { it.text })
        assertEquals(listOf(11_000L, 16_500L, 22_000L), traditional.lines.map { it.timeMs })

        val oneLine = LrcParser.parse("[00:01.00]熬過了多久患難[00:05.00]才慢慢學會放手[00:09][00:40.00]重複這一句")
        assertTrue(oneLine.synced)
        assertEquals(listOf("熬過了多久患難", "才慢慢學會放手", "重複這一句", "重複這一句"), oneLine.lines.map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 9_000L, 40_000L), oneLine.lines.map { it.timeMs })

        val gaps = LrcParser.parse("[00:01.00]\n[00:02.00]有字\n[00:02.00]同一秒\n[00:03.00]   \n")
        assertEquals(listOf("有字", "同一秒"), gaps.lines.map { it.text })
        assertEquals(1, gaps.currentIndex(2_000))

        val escaped = LrcParser.parse("[00:01.00]燈讓口丁\\n[00:04.00]人人卻迎得難心")
        assertTrue(escaped.synced)
        assertEquals(2, escaped.lines.size)
    }

    @Test
    fun lyricScrollKeepsTheActiveLineCentered() {
        val viewport = 400f
        val line = 52f
        val active = 72f
        for (index in listOf(0, 1, 12, 40)) {
            val shift = lyricTranslationY(index, viewport, line, active)
            val center = shift + index * line + active / 2f
            assertEquals(viewport / 2f, center, 0.01f)
        }
    }

    @Test
    fun plainTextIsNotSynced() {
        val lyrics = LrcParser.parse("只有一段\n没有时间轴")
        assertTrue(!lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(-1, lyrics.currentIndex(1_000))
    }

    @Test
    fun embeddedLyricsWinOverSidecar() {
        val embedded = Lyrics(false, listOf(LyricLine(null, "文件里的")))
        val sidecar = Lyrics(true, listOf(LyricLine(0, "旁边的")))
        assertEquals("文件里的", chooseLyrics(embedded, sidecar)?.plainText())
        assertNull(chooseLyrics(Lyrics(false, emptyList()), Lyrics(false, listOf(LyricLine(null, "  ")))))
        assertEquals("旁边的", chooseLyrics(null, sidecar)?.plainText())
        val cached = Lyrics(true, listOf(LyricLine(0, "缓存")))
        assertEquals("文件里的", chooseLyrics(embedded, sidecar, cached)?.plainText())
        assertEquals("缓存", chooseLyrics(null, null, cached)?.plainText())
    }

    @Test
    fun lrclibPrefersSyncedTextAndCloseDuration() {
        val body = """
            [
              {
                "trackName": "夜",
                "artistName": "某人",
                "duration": 80,
                "instrumental": false,
                "plainLyrics": "纯文本",
                "syncedLyrics": "[00:01.00]第一行\n[00:03.00]第二行"
              },
              {
                "trackName": "夜",
                "artistName": "某人",
                "duration": 200,
                "instrumental": false,
                "plainLyrics": "另一首",
                "syncedLyrics": null
              }
            ]
        """.trimIndent()
        val hits = dev.localmusic.player.lyrics.LrcLibParser.parseBody(body)
        assertEquals(2, hits.size)
        assertEquals("[00:01.00]第一行\n[00:03.00]第二行", hits[0].bestText())
        val chosen = dev.localmusic.player.lyrics.LrcLibParser.chooseHit(hits, 78)
        assertEquals(80, chosen?.durationSec)
        assertNull(dev.localmusic.player.lyrics.LrcLibParser.chooseHit(hits, 140))
    }

    @Test
    fun id3UnsyncedAndSynced() {
        val uslt = id3v23(
            "USLT",
            byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) +
                "窗外有雨".toByteArray(),
        )
        val unsynced = EmbeddedLyricsReader.read(ByteSource(uslt))
        assertNotNull(unsynced)
        assertTrue(!unsynced!!.synced)
        assertEquals("窗外有雨", unsynced.plainText())

        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 2, 1, 0))
        body.write(terminated("第一行"))
        writeBe32(body, 1500)
        body.write(terminated("第二行"))
        writeBe32(body, 4200)
        val sylt = EmbeddedLyricsReader.read(ByteSource(id3v23("SYLT", body.toByteArray())))
        assertNotNull(sylt)
        assertTrue(sylt!!.synced)
        assertEquals(1500L, sylt.lines[0].timeMs)
        assertEquals("第二行", sylt.lines[1].text)
        assertEquals(1, sylt.currentIndex(4_200))
    }

    @Test
    fun flacOggMp4AndApe() {
        val flac = EmbeddedLyricsReader.read(ByteSource(flacComment("[00:02.00]河面")))
        assertEquals(2_000L, flac?.lines?.first()?.timeMs)
        assertTrue(flac?.synced == true)

        val ogg = EmbeddedLyricsReader.read(ByteSource(oggComment("纯文本歌词")))
        assertEquals("纯文本歌词", ogg?.plainText())
        assertTrue(ogg?.synced == false)

        val mp4 = EmbeddedLyricsReader.read(ByteSource(mp4Lyric("盒子里的句子")))
        assertEquals("盒子里的句子", mp4?.plainText())

        val ape = EmbeddedLyricsReader.read(ByteSource(apeLyric("[00:04.25]末尾标签")))
        assertEquals(4_250L, ape?.lines?.first()?.timeMs)
    }

    @Test
    fun quietToneScoresBelowBursts() {
        val quiet = featuresOf(tone(amplitude = 0.02f, bursts = false))
        val loud = featuresOf(tone(amplitude = 0.85f, bursts = true))
        assertTrue("loud=${loud.score} quiet=${quiet.score}", loud.score > quiet.score + 0.2f)
        assertTrue(loud.onsetPerSec > quiet.onsetPerSec)
        assertTrue(loud.rms > quiet.rms)
    }

    @Test
    fun energyQueueAlternatesCalmThenEnergetic() {
        data class Song(val name: String, val score: Float?)
        val songs = listOf(
            Song("a", 0.10f),
            Song("b", 0.20f),
            Song("c", 0.80f),
            Song("d", 0.90f),
            Song("pending", null),
        )
        val order = EnergyMath.arrange(songs, { it.score }, Random(4))
        assertEquals(
            listOf(EnergyGroup.CALM, EnergyGroup.ENERGETIC, EnergyGroup.CALM, EnergyGroup.ENERGETIC, EnergyGroup.UNKNOWN),
            order.map { it.group },
        )
        assertEquals("pending", order.last().item.name)
        assertTrue(order.filter { it.group == EnergyGroup.CALM }.all { (it.score ?: 1f) <= 0.2f })
        assertTrue(order.filter { it.group == EnergyGroup.ENERGETIC }.all { (it.score ?: 0f) >= 0.8f })
    }

    @Test
    fun tiedScoresStillSplitAndGroupsAreShuffled() {
        data class Song(val name: String, val score: Float?)
        val tied = (1..4).map { Song("s$it", 0.4f) }
        val order = EnergyMath.arrange(tied, { it.score }, Random(3))
        assertEquals(listOf(EnergyGroup.CALM, EnergyGroup.ENERGETIC, EnergyGroup.CALM, EnergyGroup.ENERGETIC), order.map { it.group })

        val calm = (1..6).map { Song("c$it", 0.1f) }
        val loud = (1..6).map { Song("e$it", 0.95f) }
        val first = EnergyMath.arrange(calm + loud, { it.score }, Random(1))
            .filter { it.group == EnergyGroup.CALM }
            .map { it.item.name }
        val second = EnergyMath.arrange(calm + loud, { it.score }, Random(99))
            .filter { it.group == EnergyGroup.CALM }
            .map { it.item.name }
        assertNotEquals(first, second)
    }

    @Test
    fun unanalyzedTracksStayTogetherAtTheEnd() {
        data class Song(val name: String, val score: Float?)
        val songs = listOf(Song("x", null), Song("y", null), Song("z", null))
        val order = EnergyMath.arrange(songs, { it.score }, Random(8))
        assertEquals(3, order.size)
        assertTrue(order.all { it.group == EnergyGroup.UNKNOWN })
    }

    private fun featuresOf(wav: ByteArray) = run {
        val source = ByteSource(wav)
        val info = WavPcm.parse(source)!!
        val samples = PcmSampler.concat(
            PcmSampler.plan(info.totalFrames, info.sampleRate).map { range ->
                WavPcm.readMono(source, info, range.first, (range.last - range.first + 1).toInt())
            },
        )
        EnergyMath.features(samples, info.sampleRate)
    }

    private fun tone(amplitude: Float, bursts: Boolean): ByteArray {
        val rate = 44100
        val seconds = 6
        val pcm = ShortArray(rate * seconds)
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            val gate = if (!bursts) 1.0 else {
                val phase = t % 0.28
                if (phase < 0.045) 1.0 else 0.0
            }
            val sample = sin(2.0 * Math.PI * 180.0 * t) * amplitude * gate
            pcm[i] = (sample * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return wav16(rate, pcm)
    }

    private fun wav16(sampleRate: Int, samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        writeLe32(out, 36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeLe32(out, 16)
        writeLe16(out, 1)
        writeLe16(out, 1)
        writeLe32(out, sampleRate)
        writeLe32(out, sampleRate * 2)
        writeLe16(out, 2)
        writeLe16(out, 16)
        out.write("data".toByteArray())
        writeLe32(out, dataBytes)
        for (sample in samples) writeLe16(out, sample.toInt() and 0xFFFF)
        return out.toByteArray()
    }
}

private fun id3v23(frameId: String, body: ByteArray): ByteArray {
    val frame = ByteArrayOutputStream()
    frame.write(frameId.toByteArray())
    writeBe32(frame, body.size)
    frame.write(0)
    frame.write(0)
    frame.write(body)
    val frames = frame.toByteArray()
    val header = ByteArrayOutputStream()
    header.write("ID3".toByteArray())
    header.write(3)
    header.write(0)
    header.write(0)
    val size = frames.size
    header.write((size shr 21) and 0x7F)
    header.write((size shr 14) and 0x7F)
    header.write((size shr 7) and 0x7F)
    header.write(size and 0x7F)
    header.write(frames)
    return header.toByteArray()
}

private fun flacComment(lyrics: String): ByteArray {
    val vendor = "test".toByteArray()
    val comment = "LYRICS=$lyrics".toByteArray()
    val block = ByteArrayOutputStream()
    writeLe32(block, vendor.size)
    block.write(vendor)
    writeLe32(block, 1)
    writeLe32(block, comment.size)
    block.write(comment)
    val data = block.toByteArray()
    val out = ByteArrayOutputStream()
    out.write("fLaC".toByteArray())
    out.write(0x84)
    out.write((data.size shr 16) and 0xFF)
    out.write((data.size shr 8) and 0xFF)
    out.write(data.size and 0xFF)
    out.write(data)
    return out.toByteArray()
}

private fun oggComment(lyrics: String): ByteArray {
    val packet = ByteArrayOutputStream()
    packet.write(0x03)
    packet.write("vorbis".toByteArray())
    val vendor = "test".toByteArray()
    writeLe32(packet, vendor.size)
    packet.write(vendor)
    val comment = "LYRICS=$lyrics".toByteArray()
    writeLe32(packet, 1)
    writeLe32(packet, comment.size)
    packet.write(comment)
    val payload = packet.toByteArray()
    val page = ByteArrayOutputStream()
    page.write("OggS".toByteArray())
    page.write(0)
    page.write(0x02)
    page.write(ByteArray(8 + 4 + 4 + 4))
    page.write(1)
    page.write(payload.size)
    page.write(payload)
    return page.toByteArray()
}

private fun mp4Lyric(text: String): ByteArray {
    val dataBody = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + text.toByteArray()
    val data = atom("data", dataBody)
    val lyr = atom(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), data)
    val ilst = atom("ilst", lyr)
    val meta = atom("meta", ByteArray(4) + ilst)
    val udta = atom("udta", meta)
    val moov = atom("moov", udta)
    val ftyp = atom("ftyp", "M4A ".toByteArray() + byteArrayOf(0, 0, 0, 0) + "M4A ".toByteArray())
    return ftyp + moov
}

private fun apeLyric(lyrics: String): ByteArray {
    val value = lyrics.toByteArray()
    val item = ByteArrayOutputStream()
    writeLe32(item, value.size)
    writeLe32(item, 0)
    item.write("LYRICS".toByteArray())
    item.write(0)
    item.write(value)
    val items = item.toByteArray()
    val footer = ByteArrayOutputStream()
    footer.write("APETAGEX".toByteArray())
    writeLe32(footer, 2000)
    writeLe32(footer, items.size + 32)
    writeLe32(footer, 1)
    writeLe32(footer, 0)
    footer.write(ByteArray(8))
    return items + footer.toByteArray()
}

private fun atom(type: String, body: ByteArray): ByteArray = atom(type.toByteArray(Charsets.ISO_8859_1), body)

private fun atom(type: ByteArray, body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    writeBe32(out, 8 + body.size)
    out.write(type)
    out.write(body)
    return out.toByteArray()
}

private fun terminated(text: String): ByteArray = text.toByteArray() + byteArrayOf(0)

private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
}
