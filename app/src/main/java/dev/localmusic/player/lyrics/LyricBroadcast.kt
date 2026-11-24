package dev.localmusic.player.lyrics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Timed lyrics the UI has already resolved, including a later online fetch.
 * The playback service loads lyrics on its own for background playback, and
 * also listens here so a fetch that finishes after that load can still scroll.
 */
object LyricBroadcast {
    data class Update(val mediaId: String, val lyrics: Lyrics)

    private val _updates = MutableSharedFlow<Update>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<Update> = _updates.asSharedFlow()

    fun publish(mediaId: String, lyrics: Lyrics) {
        if (!lyrics.synced || !lyrics.hasText()) return
        _updates.tryEmit(Update(mediaId, lyrics))
    }
}
