package dev.localmusic.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntry(
    val mediaIndex: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val isCurrent: Boolean,
)

data class NowPlaying(
    val connected: Boolean = false,
    val hasTrack: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val mediaId: String? = null,
    val queue: List<QueueEntry> = emptyList(),
    val error: String? = null,
)

class PlayerSession(context: Context) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var error: String? = null

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playerError == null && player.isPlaying) error = null
            publish(player)
        }

        override fun onPlayerError(playbackError: PlaybackException) {
            val player = controller ?: return
            val title = player.mediaMetadata.title?.toString().orEmpty()
            error = if (title.isBlank()) "无法播放这首" else "无法播放「$title」"
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            publish(player)
        }
    }

    init {
        scope.launch {
            while (isActive) {
                controller?.let { publish(it) }
                delay(80)
            }
        }
    }

    fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener(
            {
                try {
                    val created = pending.get()
                    controller = created
                    created.addListener(listener)
                    publish(created)
                } catch (_: Exception) {
                    error = "播放器没有连上"
                    _state.value = NowPlaying(error = error)
                    future = null
                }
            },
            executor,
        )
    }

    fun play(items: List<MediaItem>, index: Int, mode: PlayMode) {
        val player = controller ?: return
        if (items.isEmpty()) return
        val start = index.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, start, 0L)
        applyTransport(player, mode)
        player.prepare()
        player.play()
        error = null
        publish(player)
    }

    fun replace(items: List<MediaItem>, mode: PlayMode, keepPosition: Boolean) {
        val player = controller ?: return
        if (items.isEmpty()) return
        if (reorderInPlace(player, items)) {
            applyTransport(player, mode)
            publish(player)
            return
        }
        val currentId = player.currentMediaItem?.mediaId
        val currentOccurrence = occurrenceIndex(player, player.currentMediaItemIndex)
        val position = if (keepPosition) player.currentPosition.coerceAtLeast(0L) else 0L
        val start = matchingOccurrence(items, currentId, currentOccurrence)
        val wasPlaying = player.isPlaying || player.playWhenReady
        player.setMediaItems(items, start, position)
        applyTransport(player, mode)
        player.prepare()
        if (wasPlaying) player.play()
        publish(player)
    }

    /**
     * Reorder to [items] when it is the same multiset of occurrences.
     * Uses moveMediaItem only, so the current renderer is left alone.
     */
    fun reorderTo(items: List<MediaItem>, mode: PlayMode) {
        val player = controller ?: return
        if (items.isEmpty()) return
        if (!reorderInPlace(player, items)) {
            replace(items, mode, keepPosition = true)
            return
        }
        applyTransport(player, mode)
        publish(player)
    }

    private fun reorderInPlace(player: Player, items: List<MediaItem>): Boolean {
        if (player.mediaItemCount == 0) return false
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val moves = PlaylistPermutation.moves(currentIds, items.map { it.mediaId }) ?: return false
        for ((from, to) in moves) {
            player.moveMediaItem(from, to)
        }
        return true
    }

    fun applyMode(mode: PlayMode) {
        controller?.let {
            applyTransport(it, mode)
            publish(it)
        }
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
        publish(player)
    }

    fun next() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.mediaItemCount > 0) {
            player.seekTo(0, 0L)
        }
        publish(player)
    }

    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > 3_000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
        publish(player)
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        publish(player)
    }

    fun playQueueIndex(visualIndex: Int) {
        val player = controller ?: return
        val order = playbackOrder(player)
        val mediaIndex = order.getOrNull(visualIndex) ?: return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.seekTo(mediaIndex, 0L)
        player.play()
        error = null
        publish(player)
    }

    /** Reorders the audible queue. Returns true when the list changed. Does not touch files. */
    fun moveQueue(visualIndex: Int, delta: Int): Boolean {
        val player = controller ?: return false
        materializeAudibleOrder(player)
        val to = visualIndex + delta
        if (visualIndex !in 0 until player.mediaItemCount || to !in 0 until player.mediaItemCount) return false
        player.moveMediaItem(visualIndex, to)
        publish(player)
        return true
    }

    /** Drops one queue entry. Returns true when the list changed. Does not touch files. */
    fun removeQueue(visualIndex: Int): Boolean {
        val player = controller ?: return false
        materializeAudibleOrder(player)
        val count = player.mediaItemCount
        if (visualIndex !in 0 until count) return false
        val removingCurrent = player.currentMediaItemIndex == visualIndex
        val removingLast = visualIndex == count - 1
        player.removeMediaItem(visualIndex)
        if (player.mediaItemCount == 0) {
            player.stop()
            publish(player)
            return true
        }
        if (removingCurrent && removingLast) {
            player.seekTo(player.mediaItemCount - 1, 0L)
            if (player.playWhenReady) player.play()
        }
        publish(player)
        return true
    }

    /** Turn a shuffled timeline into the same audible playlist order without replacing media. */
    private fun materializeAudibleOrder(player: Player) {
        if (!player.shuffleModeEnabled) return
        val order = playbackOrder(player)
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val target = order.map { ids[it] }
        val moves = PlaylistPermutation.moves(ids, target).orEmpty()
        for ((from, to) in moves) player.moveMediaItem(from, to)
        player.shuffleModeEnabled = false
    }

    fun remove(mediaId: String) {
        val player = controller ?: return
        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId == mediaId) {
                player.removeMediaItem(index)
                break
            }
        }
        publish(player)
    }

    private fun applyTransport(player: Player, mode: PlayMode) {
        player.shuffleModeEnabled = mode == PlayMode.SHUFFLE
        player.repeatMode = when (mode) {
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            PlayMode.REPEAT_ALL, PlayMode.SHUFFLE, PlayMode.ENERGY -> Player.REPEAT_MODE_ALL
        }
    }

    private fun publish(player: Player) {
        val index = player.currentMediaItemIndex
        val item = if (index in 0 until player.mediaItemCount) player.currentMediaItem else null
        val duration = player.duration
        val safeDuration = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L
        val queue = playbackOrder(player).map { mediaIndex ->
            val media = player.getMediaItemAt(mediaIndex)
            QueueEntry(
                mediaIndex = mediaIndex,
                mediaId = media.mediaId,
                title = media.mediaMetadata.title?.toString().orEmpty(),
                artist = media.mediaMetadata.artist?.toString().orEmpty(),
                isCurrent = mediaIndex == index,
            )
        }
        _state.value = NowPlaying(
            connected = true,
            hasTrack = item != null,
            isPlaying = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = safeDuration,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            album = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
            mediaId = item?.mediaId,
            queue = queue,
            error = error,
        )
    }

    private fun occurrenceIndex(player: Player, mediaIndex: Int): Int {
        if (mediaIndex !in 0 until player.mediaItemCount) return 0
        val id = player.getMediaItemAt(mediaIndex).mediaId
        var seen = 0
        for (index in 0 until mediaIndex) {
            if (player.getMediaItemAt(index).mediaId == id) seen++
        }
        return seen
    }

    private fun matchingOccurrence(items: List<MediaItem>, mediaId: String?, occurrence: Int): Int {
        if (mediaId == null) return 0
        var seen = 0
        items.forEachIndexed { index, item ->
            if (item.mediaId == mediaId) {
                if (seen == occurrence) return index
                seen++
            }
        }
        return items.indexOfFirst { it.mediaId == mediaId }.let { if (it < 0) 0 else it }
    }

    private fun playbackOrder(player: Player): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.windowCount == 0 || player.mediaItemCount == 0) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        var guard = 0
        while (index != C.INDEX_UNSET && guard++ <= timeline.windowCount) {
            if (index in 0 until player.mediaItemCount) order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }
}
