package dev.localmusic.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Publishes a lyric window on [getMediaMetadata] without touching the media item.
 *
 * Media3's legacy session, which Bluetooth AVRCP reads, calls [getMediaMetadata]
 * and sends [MediaMetadata.title] as METADATA_KEY_TITLE. The phone notification
 * and lock screen prefer [MediaMetadata.displayTitle], so that field stays the
 * song name. Media item metadata is left alone, and that is what the in-app
 * player reads.
 *
 * Listeners are told that metadata changed, but the object passed to them is the
 * real song metadata. Media3 controllers (the in-app session) keep the song
 * title. The legacy stub ignores that argument and reads [getMediaMetadata].
 */
class LyricMetadataPlayer(player: Player) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var dispatching = false
    private var pendingDispatch = false

    @Volatile private var released = false
    @Volatile private var bluetoothTitle: String? = null
    @Volatile private var bluetoothMediaId: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != bluetoothMediaId) {
                    bluetoothTitle = null
                    bluetoothMediaId = null
                }
            }
        })
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val lyric = bluetoothTitle
        val id = bluetoothMediaId
        val current = currentMediaItem?.mediaId
        if (lyric.isNullOrEmpty() || id == null || id != current) return base
        return base.buildUpon()
            .setTitle(lyric)
            .setDisplayTitle(base.title ?: lyric)
            .build()
    }

    /**
     * [title] null restores the song title on the Bluetooth session.
     * [mediaId] must be the track the lyric belongs to.
     */
    fun setBluetoothTitle(mediaId: String?, title: String?) {
        if (released) return
        val normalized = title?.takeIf { it.isNotBlank() }
        val id = if (normalized == null) null else mediaId
        if (bluetoothTitle == normalized && bluetoothMediaId == id) return
        bluetoothTitle = normalized
        bluetoothMediaId = id
        dispatchMetadata()
    }

    override fun release() {
        released = true
        bluetoothTitle = null
        bluetoothMediaId = null
        super.release()
    }

    private fun dispatchMetadata() {
        if (dispatching) {
            pendingDispatch = true
            return
        }
        dispatching = true
        try {
            do {
                pendingDispatch = false
                val published = super.getMediaMetadata()
                for (listener in listeners) {
                    listener.onMediaMetadataChanged(published)
                }
            } while (pendingDispatch && !released)
        } finally {
            dispatching = false
        }
    }
}
