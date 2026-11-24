package dev.localmusic.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.localmusic.player.LocalMusicApp
import dev.localmusic.player.MainActivity
import dev.localmusic.player.R
import dev.localmusic.player.lyrics.LyricBroadcast
import dev.localmusic.player.lyrics.Lyrics
import dev.localmusic.player.lyrics.LyricsLoader
import dev.localmusic.player.lyrics.bluetoothTitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var lyricPlayer: LyricMetadataPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var featureEnabled = false
    private var bluetoothConnected = false
    private var lyrics: Lyrics? = null
    private var lyricsMediaId: String? = null
    private var loadingForId: String? = null
    private var loadGeneration = 0
    private var pendingBroadcast: LyricBroadcast.Update? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == BluetoothLyricPreference.KEY) {
            featureEnabled = BluetoothLyricPreference.read(this)
            publishLyricTitle()
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }

    private val tick = object : Runnable {
        override fun run() {
            val id = lyricPlayer?.currentMediaItem?.mediaId
            if (id != loadingForId) onTrackChanged(id) else publishLyricTitle()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onTrackChanged(mediaItem?.mediaId)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                publishLyricTitle()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(SongTitleNotificationProvider(this))
        val compressor = (application as LocalMusicApp).compressor
        val compressorProcessor = CompressorAudioProcessor { compressor.live }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                // Media3 skips the user processor chain when float output is on.
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(compressorProcessor))
                    .build()
            }
        }
        val exoPlayer = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val player = LyricMetadataPlayer(exoPlayer)
        lyricPlayer = player
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .build()
        player.addListener(playerListener)

        featureEnabled = BluetoothLyricPreference.read(this)
        getSharedPreferences(BluetoothLyricPreference.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(audioCallback, handler)
        bluetoothConnected = BluetoothAudioRoute.isOutputActive(this)
        scope.launch {
            LyricBroadcast.updates.collect { update ->
                pendingBroadcast = update
                applyBroadcast()
            }
        }
        handler.post(tick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioCallback)
        getSharedPreferences(BluetoothLyricPreference.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        lyricPlayer = null
        super.onDestroy()
    }

    private fun refreshRoute() {
        bluetoothConnected = BluetoothAudioRoute.isOutputActive(this)
        publishLyricTitle()
    }

    private fun onTrackChanged(mediaId: String?) {
        if (mediaId == loadingForId) {
            publishLyricTitle()
            return
        }
        loadingForId = mediaId
        val generation = ++loadGeneration
        lyrics = null
        lyricsMediaId = null
        applyBroadcast()
        publishLyricTitle()
        val trackId = mediaId?.toLongOrNull() ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadLyrics(trackId) }
            if (generation != loadGeneration) return@launch
            if (loaded != null && loaded.hasText()) {
                lyrics = loaded
                lyricsMediaId = mediaId
            }
            publishLyricTitle()
        }
    }

    private fun applyBroadcast() {
        val update = pendingBroadcast ?: return
        if (update.mediaId != lyricPlayer?.currentMediaItem?.mediaId) return
        if (!update.lyrics.synced || !update.lyrics.hasText()) return
        lyrics = update.lyrics
        lyricsMediaId = update.mediaId
        publishLyricTitle()
    }

    private suspend fun loadLyrics(trackId: Long): Lyrics? {
        val app = application as? LocalMusicApp ?: return null
        val track = runCatching { app.repository.track(trackId) }.getOrNull() ?: return null
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return null
        return runCatching {
            LyricsLoader.loadLocal(this, uri, track.treeUri, track.fileName, track.uri).lyrics
        }.getOrNull()
    }

    private fun publishLyricTitle() {
        val player = lyricPlayer ?: return
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val song = metadata?.title?.toString().orEmpty()
        val active = player.playbackState == Player.STATE_READY ||
            player.playbackState == Player.STATE_BUFFERING
        val titles = bluetoothTitles(
            songTitle = song,
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            lyrics = lyrics,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            enabled = featureEnabled,
            bluetoothConnected = bluetoothConnected,
            playbackActive = item != null && active,
            lyricMediaId = lyricsMediaId,
            currentMediaId = item?.mediaId,
        )
        player.setBluetoothTitle(item?.mediaId, titles.avrcpTitle.takeIf { titles.scrolling })
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "local_music_playback"
        private const val TICK_MS = 200L
    }
}

/**
 * The notification is built from [Player.getMediaMetadata]. While the car title
 * is a lyric window, the song name is [MediaMetadata.displayTitle].
 */
private class SongTitleNotificationProvider(context: Context) : DefaultMediaNotificationProvider(
    context,
    NotificationIdProvider { DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
    PlaybackService.CHANNEL_ID,
    R.string.notification_channel,
) {
    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence {
        val display = metadata.displayTitle
        if (!display.isNullOrBlank()) return display
        return metadata.title ?: ""
    }
}
