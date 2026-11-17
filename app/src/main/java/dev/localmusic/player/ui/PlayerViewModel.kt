package dev.localmusic.player.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localmusic.player.LocalMusicApp
import dev.localmusic.player.data.MusicRepository
import dev.localmusic.player.data.TrackEntity
import dev.localmusic.player.dsp.CompressorParams
import dev.localmusic.player.dsp.CompressorSettings
import dev.localmusic.player.energy.EnergyGroup
import dev.localmusic.player.energy.EnergyMath
import dev.localmusic.player.energy.ScoredItem
import dev.localmusic.player.library.AlbumArt
import dev.localmusic.player.library.TrackSort
import dev.localmusic.player.lyrics.LocalLyricLookup
import dev.localmusic.player.lyrics.LrcLibClient
import dev.localmusic.player.lyrics.LyricOrigin
import dev.localmusic.player.lyrics.Lyrics
import dev.localmusic.player.lyrics.LyricsCache
import dev.localmusic.player.lyrics.LyricsLoader
import dev.localmusic.player.playback.NowPlaying
import dev.localmusic.player.playback.PlayMode
import dev.localmusic.player.playback.modeRetargetsQueue
import dev.localmusic.player.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: MusicRepository = (app as LocalMusicApp).repository
    private val session = (app as LocalMusicApp).session
    private val compressorSettings: CompressorSettings = (app as LocalMusicApp).compressor
    val compressor: StateFlow<CompressorParams> = compressorSettings.state
    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    val playlists = repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val energies = repository.energies.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress = repository.progress
    val scanning = repository.scanning
    val scanCount = repository.scanCount
    val player: StateFlow<NowPlaying> = session.state

    private val _mode = MutableStateFlow(PlayMode.fromStored(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _activePlaylistId = MutableStateFlow(prefs.getLong(KEY_PLAYLIST, -1L).takeIf { it > 0 })
    val activePlaylistId: StateFlow<Long?> = _activePlaylistId.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricOrigin = MutableStateFlow<LyricOrigin?>(null)
    val lyricOrigin: StateFlow<LyricOrigin?> = _lyricOrigin.asStateFlow()

    private val _lyricFetch = MutableStateFlow(LyricFetch.Idle)
    val lyricFetch: StateFlow<LyricFetch> = _lyricFetch.asStateFlow()

    private val _autoLyrics = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LYRICS, false))
    val autoLyrics: StateFlow<Boolean> = _autoLyrics.asStateFlow()

    private val _trackSort = MutableStateFlow(TrackSort.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: TrackSort.PLAYLIST)
    val trackSort: StateFlow<TrackSort> = _trackSort.asStateFlow()

    private val _openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openPlayer: SharedFlow<Unit> = _openPlayer.asSharedFlow()

    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art.asStateFlow()

    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack: StateFlow<TrackEntity?> = _currentTrack.asStateFlow()

    private val _arrangement = MutableStateFlow<List<ScoredItem<TrackEntity>>>(emptyList())
    val arrangement: StateFlow<List<ScoredItem<TrackEntity>>> = _arrangement.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadedTrackId: Long? = null
    private var lyricGeneration = 0
    private var consumedViewUri: String? = null

    init {
        session.connect()
        repository.kickAnalysis()
        viewModelScope.launch {
            session.state.collect { state ->
                val id = state.mediaId?.toLongOrNull()
                if (id != null && id != loadedTrackId) {
                    loadedTrackId = id
                    loadTrackDetails(id)
                }
                if (id == null) {
                    loadedTrackId = null
                    lyricGeneration++
                    _lyrics.value = null
                    _lyricOrigin.value = null
                    _lyricFetch.value = LyricFetch.Idle
                    _art.value = null
                    _currentTrack.value = null
                }
            }
        }
        viewModelScope.launch {
            repository.batchComplete.collect {
                if (_mode.value == PlayMode.ENERGY && session.state.value.hasTrack) {
                    rebuildEnergy(keepPosition = true)
                }
            }
        }
    }

    fun tracks(playlistId: Long) = repository.observeTracks(playlistId)

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                message("先写一个列表名称")
                return@launch
            }
            repository.createPlaylist(trimmed)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
            if (_activePlaylistId.value == id) {
                _activePlaylistId.value = null
            }
        }
    }

    fun addFolder(playlistId: Long, uri: Uri) {
        viewModelScope.launch {
            val count = runCatching { repository.addFolder(playlistId, uri) }.getOrElse {
                message("无法读取这个文件夹")
                return@launch
            }
            message(if (count == 0) "没有找到新的音频文件" else "已加入 $count 首")
        }
    }

    fun addFiles(playlistId: Long, uris: List<Uri>) {
        viewModelScope.launch {
            val count = runCatching { repository.addFiles(playlistId, uris) }.getOrElse {
                message("无法读取这些文件")
                return@launch
            }
            message(if (count == 0) "没有找到新的音频文件" else "已加入 $count 首")
        }
    }

    fun removeTrack(playlistId: Long, track: TrackEntity) {
        viewModelScope.launch {
            repository.removeTrack(track.id)
            if (_activePlaylistId.value == playlistId) {
                session.remove(track.id.toString())
            }
        }
    }

    fun moveTrack(playlistId: Long, track: TrackEntity, delta: Int) {
        viewModelScope.launch {
            repository.moveTrack(playlistId, track.id, delta)
            if (_activePlaylistId.value == playlistId && _mode.value != PlayMode.SHUFFLE && _mode.value != PlayMode.ENERGY) {
                val tracks = repository.tracksOnce(playlistId)
                session.replace(tracks.map { it.toMediaItem() }, _mode.value, keepPosition = true)
            }
        }
    }

    fun setTrackSort(sort: TrackSort) {
        _trackSort.value = sort
        prefs.edit().putString(KEY_SORT, sort.name).apply()
    }

    fun playPlaylist(playlistId: Long, startTrackId: Long? = null, ordered: List<TrackEntity>? = null) {
        viewModelScope.launch {
            val tracks = ordered ?: repository.tracksOnce(playlistId)
            if (tracks.isEmpty()) {
                message("这个列表还没有歌曲")
                return@launch
            }
            rememberPlaylist(playlistId)
            val items = buildQueue(tracks, _mode.value)
            val startId = (startTrackId ?: tracks.first().id).toString()
            val index = items.indexOfFirst { it.mediaId == startId }.let { if (it < 0) 0 else it }
            session.play(items, index, _mode.value)
        }
    }

    fun playQueueAt(visualIndex: Int) {
        session.playQueueIndex(visualIndex)
    }

    fun moveQueue(visualIndex: Int, delta: Int) {
        if (session.moveQueue(visualIndex, delta)) relaxShuffleAfterManualQueueEdit()
    }

    fun removeFromQueue(visualIndex: Int) {
        if (session.removeQueue(visualIndex)) relaxShuffleAfterManualQueueEdit()
    }

    private fun relaxShuffleAfterManualQueueEdit() {
        if (_mode.value != PlayMode.SHUFFLE) return
        _mode.value = PlayMode.REPEAT_ALL
        prefs.edit().putString(KEY_MODE, PlayMode.REPEAT_ALL.name).apply()
        session.applyMode(PlayMode.REPEAT_ALL)
    }

    fun setMode(mode: PlayMode) {
        val previous = _mode.value
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        val state = session.state.value
        val playlistId = _activePlaylistId.value
        if (!state.hasTrack || playlistId == null || !modeRetargetsQueue(previous, mode)) {
            if (mode != PlayMode.SHUFFLE && mode != PlayMode.ENERGY) _arrangement.value = emptyList()
            session.applyMode(mode)
            return
        }
        viewModelScope.launch {
            val tracks = repository.tracksOnce(playlistId)
            if (tracks.isEmpty()) {
                session.applyMode(mode)
                return@launch
            }
            if (mode != PlayMode.ENERGY) _arrangement.value = emptyList()
            val items = buildQueue(tracks, mode)
            session.reorderTo(items, mode)
        }
    }

    fun openFromIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val key = uri.toString()
        if (consumedViewUri == key) return
        consumedViewUri = key
        viewModelScope.launch {
            val opened = runCatching {
                val playlistId = repository.ensurePlaylist(OPENED_PLAYLIST)
                repository.addFiles(playlistId, listOf(uri))
                repository.tracksOnce(playlistId).firstOrNull { it.uri == key }
            }.getOrNull()
            if (opened == null) {
                message("无法打开这个音频")
                return@launch
            }
            val tracks = repository.tracksOnce(playlistIdOf(opened))
            rememberPlaylist(opened.playlistId)
            val items = buildQueue(tracks, _mode.value)
            val index = items.indexOfFirst { it.mediaId == opened.id.toString() }.let { if (it < 0) 0 else it }
            session.play(items, index, _mode.value)
            _openPlayer.tryEmit(Unit)
        }
    }

    fun setCompressorEnabled(enabled: Boolean) = compressorSettings.setEnabled(enabled)

    fun setCompressorThreshold(value: Float) = compressorSettings.setThreshold(value)

    fun setCompressorRatio(value: Float) = compressorSettings.setRatio(value)

    fun setCompressorAttack(value: Float) = compressorSettings.setAttack(value)

    fun setCompressorRelease(value: Float) = compressorSettings.setRelease(value)

    fun setCompressorKnee(value: Float) = compressorSettings.setKnee(value)

    fun setCompressorMakeup(value: Float) = compressorSettings.setMakeup(value)

    fun setAutoLyrics(enabled: Boolean) {
        _autoLyrics.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_LYRICS, enabled).apply()
        val trackId = loadedTrackId
        if (enabled && trackId != null && _lyrics.value == null && _lyricFetch.value != LyricFetch.Loading) {
            fetchOnline(trackId, force = false)
        }
    }

    fun searchLyrics() {
        val trackId = loadedTrackId ?: return
        fetchOnline(trackId, force = true)
    }

    fun toggle() = session.toggle()
    fun next() = session.next()
    fun previous() = session.previous()
    fun seekTo(positionMs: Long) = session.seekTo(positionMs)

    private suspend fun rebuildEnergy(keepPosition: Boolean) {
        val playlistId = _activePlaylistId.value ?: return
        val tracks = repository.tracksOnce(playlistId)
        if (tracks.isEmpty()) return
        val items = buildQueue(tracks, PlayMode.ENERGY)
        session.replace(items, PlayMode.ENERGY, keepPosition)
    }

    private suspend fun buildQueue(tracks: List<TrackEntity>, mode: PlayMode) =
        if (mode == PlayMode.ENERGY) {
            val scores = repository.scoreMap(tracks.map { it.uri })
            val arranged = EnergyMath.arrange(tracks, { track -> scores[track.uri] }, Random(System.nanoTime()))
            _arrangement.value = arranged
            Log.i(
                "EnergyQueue",
                arranged.joinToString(" | ") { item ->
                    val score = item.score?.let { "%.3f".format(it) } ?: "-"
                    "${item.item.title}:${item.group}:$score"
                },
            )
            if (arranged.any { it.group == EnergyGroup.UNKNOWN }) repository.kickAnalysis()
            arranged.map { it.item.toMediaItem() }
        } else {
            _arrangement.value = emptyList()
            tracks.map { it.toMediaItem() }
        }

    private fun rememberPlaylist(playlistId: Long) {
        _activePlaylistId.value = playlistId
        prefs.edit().putLong(KEY_PLAYLIST, playlistId).apply()
    }

    private fun loadTrackDetails(trackId: Long) {
        val generation = ++lyricGeneration
        _lyricFetch.value = LyricFetch.Idle
        _lyricOrigin.value = null
        _lyrics.value = null
        viewModelScope.launch {
            val track = repository.track(trackId)
            if (generation != lyricGeneration) return@launch
            _currentTrack.value = track
            if (track == null) {
                _art.value = null
                return@launch
            }
            val uri = Uri.parse(track.uri)
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    LyricsLoader.loadLocal(getApplication(), uri, track.treeUri, track.fileName, track.uri)
                }.getOrDefault(LocalLyricLookup(null, null, false))
            }
            val art = withContext(Dispatchers.IO) {
                runCatching { AlbumArt.load(getApplication(), uri) }.getOrNull()
            }
            if (generation != lyricGeneration) return@launch
            _art.value = art
            if (local.lyrics != null) {
                _lyrics.value = local.lyrics
                _lyricOrigin.value = local.origin
                _lyricFetch.value = LyricFetch.Idle
            } else if (_autoLyrics.value && !local.cachedMiss) {
                fetchOnline(trackId, force = false, generation = generation)
            }
        }
    }

    private fun fetchOnline(trackId: Long, force: Boolean, generation: Int = lyricGeneration) {
        viewModelScope.launch {
            val track = _currentTrack.value?.takeIf { it.id == trackId } ?: repository.track(trackId)
            if (track == null || generation != lyricGeneration) return@launch
            if (!force) {
                val cached = withContext(Dispatchers.IO) { LyricsCache.read(getApplication(), track.uri) }
                if (cached is LyricsCache.Entry.Miss) return@launch
                if (cached is LyricsCache.Entry.Found) {
                    val parsed = withContext(Dispatchers.IO) {
                        dev.localmusic.player.lyrics.LrcParser.parse(cached.raw)
                    }
                    if (parsed.hasText() && generation == lyricGeneration) {
                        _lyrics.value = parsed
                        _lyricOrigin.value = LyricOrigin.CACHE
                        _lyricFetch.value = LyricFetch.Idle
                    }
                    return@launch
                }
            }
            val title = track.title.ifBlank { track.fileName.substringBeforeLast('.') }
            val artist = track.artist.trim().takeUnless { it.isEmpty() || it == "未知艺人" }.orEmpty()
            if (title.isBlank()) {
                message("没有歌名，无法查找")
                return@launch
            }
            _lyricFetch.value = LyricFetch.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LrcLibClient.fetch(title, artist, track.album, track.durationMs)
                }
            }
            if (generation != lyricGeneration) return@launch
            result.fold(
                onSuccess = { raw ->
                    if (raw.isNullOrBlank()) {
                        withContext(Dispatchers.IO) { LyricsCache.saveMiss(getApplication(), track.uri) }
                        _lyricFetch.value = LyricFetch.Empty
                    } else {
                        withContext(Dispatchers.IO) { LyricsCache.saveFound(getApplication(), track, raw) }
                        val parsed = dev.localmusic.player.lyrics.LrcParser.parse(raw)
                        if (parsed.hasText()) {
                            _lyrics.value = parsed
                            _lyricOrigin.value = LyricOrigin.CACHE
                            _lyricFetch.value = LyricFetch.Idle
                        } else {
                            _lyricFetch.value = LyricFetch.Empty
                        }
                    }
                },
                onFailure = {
                    Log.w("LrcLib", "查找歌词失败", it)
                    _lyricFetch.value = LyricFetch.Failed
                },
            )
        }
    }

    private fun playlistIdOf(track: TrackEntity) = track.playlistId

    companion object {
        private const val PREFS = "player"
        private const val KEY_MODE = "mode"
        private const val KEY_PLAYLIST = "playlist"
        private const val KEY_AUTO_LYRICS = "auto_lyrics"
        private const val KEY_SORT = "track_sort"
        private const val OPENED_PLAYLIST = "打开的歌曲"
    }
}

enum class LyricFetch { Idle, Loading, Empty, Failed }
