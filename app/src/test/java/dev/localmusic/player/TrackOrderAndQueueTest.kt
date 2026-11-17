package dev.localmusic.player

import dev.localmusic.player.data.TrackEntity
import dev.localmusic.player.library.TrackOrder
import dev.localmusic.player.library.TrackSort
import dev.localmusic.player.playback.PlayMode
import dev.localmusic.player.playback.PlaylistPermutation
import dev.localmusic.player.playback.QueueEdits
import dev.localmusic.player.playback.modeRetargetsQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackOrderAndQueueTest {
    private val pinyin = mapOf(
        "蔡琴" to "cai qin",
        "你的眼神" to "ni de yan shen",
        "陈奕迅" to "chen yi xun",
        "爱情转移" to "ai qing zhuan yi",
        "白" to "bai",
        "阿" to "a",
    )

    private fun latin(text: String): String {
        return pinyin[text] ?: text
    }

    @Test
    fun sortsChineseByPinyinThenLatinAndNumbers() {
        val tracks = listOf(
            song(1, "爱情转移", "陈奕迅", 0),
            song(2, "你的眼神", "蔡琴", 1),
            song(3, "Banana", "apple", 2),
            song(4, "apple", "Banana", 3),
            song(5, "歌10", "白", 4),
            song(6, "歌2", "阿", 5),
            song(7, "", "", 6),
        )
        val byTitle = TrackOrder.sorted(tracks, TrackSort.TITLE).map { it.id }
        // Latin (case-insensitive) first, then Han by pinyin. 歌2 before 歌10. Missing title uses the file name.
        assertEquals(listOf(4L, 3L, 7L, 1L, 6L, 5L, 2L), byTitle)
        assertTrue(byTitle.indexOf(6L) < byTitle.indexOf(5L))
        assertTrue(byTitle.indexOf(1L) < byTitle.indexOf(2L))

        val byArtist = TrackOrder.sorted(tracks, TrackSort.ARTIST).map { it.artist.ifBlank { "#" } }
        assertEquals(listOf("apple", "Banana", "阿", "白", "蔡琴", "陈奕迅", "#"), byArtist)

        val playlist = TrackOrder.sorted(tracks, TrackSort.PLAYLIST).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), playlist)
    }

    @Test
    fun searchMatchesTitleOrArtistAndKeepsSort() {
        val tracks = listOf(
            song(1, "爱情转移", "陈奕迅"),
            song(2, "你的眼神", "蔡琴"),
            song(3, "歌2", "蔡琴"),
            song(4, "Night", "Cai Qin"),
        )
        val fold = { text: String -> latin(text) }
        val chinese = TrackOrder.arrange(tracks, TrackSort.TITLE, "蔡", fold)
        assertEquals(listOf(3L, 2L), chinese.map { it.id })

        val pinyinHit = TrackOrder.arrange(tracks, TrackSort.TITLE, "yan", fold)
        assertEquals(listOf("你的眼神"), pinyinHit.map { it.title })

        val latinHit = TrackOrder.arrange(tracks, TrackSort.ARTIST, "cai", fold)
        assertTrue(latinHit.map { it.id }.containsAll(listOf(2L, 3L, 4L)))
        assertEquals(emptyList<Long>(), TrackOrder.arrange(tracks, TrackSort.TITLE, "不存在", fold).map { it.id })
    }

    @Test
    fun permutationPreservesDuplicateOccurrencesAndSkipsReplaceWhenUnchanged() {
        assertEquals(emptyList<Pair<Int, Int>>(), PlaylistPermutation.moves(listOf("a", "b"), listOf("a", "b")))
        assertNull(PlaylistPermutation.moves(listOf("a", "b"), listOf("a")))
        assertNull(PlaylistPermutation.moves(listOf("a", "a"), listOf("a", "b")))
        assertEquals(listOf(2 to 1), PlaylistPermutation.moves(listOf("a", "b", "a"), listOf("a", "a", "b")))
        assertEquals(listOf(1 to 0), PlaylistPermutation.moves(listOf("a", "b", "a"), listOf("b", "a", "a")))
    }

    @Test
    fun repeatAndShuffleDoNotRetargetTheQueue() {
        assertTrue(!modeRetargetsQueue(PlayMode.SEQUENTIAL, PlayMode.REPEAT_ONE))
        assertTrue(!modeRetargetsQueue(PlayMode.REPEAT_ALL, PlayMode.SHUFFLE))
        assertTrue(!modeRetargetsQueue(PlayMode.SHUFFLE, PlayMode.REPEAT_ONE))
        assertTrue(modeRetargetsQueue(PlayMode.SEQUENTIAL, PlayMode.ENERGY))
        assertTrue(modeRetargetsQueue(PlayMode.ENERGY, PlayMode.SEQUENTIAL))
    }

    @Test
    fun queueMoveAndRemoveKeepIndexAndDoNotImplyFileDeletion() {
        val queue = listOf("a", "b", "a", "c")
        assertNull(QueueEdits.move(queue, current = 0, from = 0, delta = -1))
        assertNull(QueueEdits.move(queue, current = 3, from = 3, delta = 1))

        val moved = QueueEdits.move(queue, current = 1, from = 2, delta = -1)!!
        assertEquals(listOf("a", "a", "b", "c"), moved.items)
        assertEquals(2, moved.currentIndex)
        assertTrue(!moved.restart)

        val movedCurrent = QueueEdits.move(queue, current = 0, from = 0, delta = 1)!!
        assertEquals(listOf("b", "a", "a", "c"), movedCurrent.items)
        assertEquals(1, movedCurrent.currentIndex)

        val removedOther = QueueEdits.remove(queue, current = 2, index = 0)!!
        assertEquals(listOf("b", "a", "c"), removedOther.items)
        assertEquals(1, removedOther.currentIndex)
        assertTrue(!removedOther.restart)

        val removedCurrent = QueueEdits.remove(queue, current = 1, index = 1)!!
        assertEquals(listOf("a", "a", "c"), removedCurrent.items)
        assertEquals(1, removedCurrent.currentIndex)
        assertTrue(removedCurrent.restart)

        val removedLast = QueueEdits.remove(queue, current = 3, index = 3)!!
        assertEquals(listOf("a", "b", "a"), removedLast.items)
        assertEquals(2, removedLast.currentIndex)
        assertTrue(removedLast.restart)

        val emptied = QueueEdits.remove(listOf("only"), current = 0, index = 0)!!
        assertTrue(emptied.items.isEmpty())
        assertEquals(-1, emptied.currentIndex)
    }

    private fun song(id: Long, title: String, artist: String, position: Int = id.toInt()) = TrackEntity(
        id = id,
        playlistId = 1,
        uri = "content://local/$id",
        treeUri = null,
        fileName = if (title.isBlank()) "track$id.flac" else "$title.flac",
        title = title,
        artist = artist,
        album = "",
        durationMs = 1_000,
        mime = "audio/flac",
        bitrateBps = null,
        sampleRate = null,
        position = position,
    )
}
