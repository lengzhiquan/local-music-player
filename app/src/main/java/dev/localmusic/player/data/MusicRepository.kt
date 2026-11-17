package dev.localmusic.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dev.localmusic.player.energy.EnergyAnalyzer
import dev.localmusic.player.library.FolderScanner
import dev.localmusic.player.library.ScannedTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AnalysisProgress(
    val done: Int,
    val total: Int,
    val running: Boolean,
)

class MusicRepository(
    private val context: Context,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    val playlists = db.playlists().observeSummaries()
    val energies = db.energy().observeAll()

    private val _progress = MutableStateFlow(AnalysisProgress(0, 0, false))
    val progress: StateFlow<AnalysisProgress> = _progress.asStateFlow()

    private val _batchComplete = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchComplete: SharedFlow<Unit> = _batchComplete.asSharedFlow()

    private val _scanCount = MutableStateFlow(0)
    val scanCount: StateFlow<Int> = _scanCount.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun observeTracks(playlistId: Long) = db.tracks().observe(playlistId)

    suspend fun tracksOnce(playlistId: Long): List<TrackEntity> = db.tracks().list(playlistId)

    suspend fun track(id: Long): TrackEntity? = db.tracks().get(id)

    suspend fun ensurePlaylist(name: String): Long {
        return db.playlists().findByName(name)?.id ?: createPlaylist(name)
    }

    suspend fun createPlaylist(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error("名称为空")
        return db.playlists().insert(
            PlaylistEntity(name = trimmed, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun deletePlaylist(id: Long) {
        db.playlists().deleteById(id)
    }

    suspend fun addFolder(playlistId: Long, treeUri: Uri): Int {
        persist(treeUri)
        _scanning.value = true
        _scanCount.value = 0
        return try {
            val scanned = withContext(Dispatchers.IO) {
                FolderScanner.scanTree(context, treeUri) { count -> _scanCount.value = count }
            }
            val inserted = insertScanned(playlistId, scanned)
            kickAnalysis()
            inserted
        } finally {
            _scanning.value = false
        }
    }

    suspend fun addFiles(playlistId: Long, uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        uris.forEach(::persist)
        _scanning.value = true
        return try {
            val scanned = withContext(Dispatchers.IO) { FolderScanner.scanFiles(context, uris) }
            val inserted = insertScanned(playlistId, scanned)
            kickAnalysis()
            inserted
        } finally {
            _scanning.value = false
        }
    }

    suspend fun removeTrack(trackId: Long) {
        db.tracks().delete(trackId)
    }

    suspend fun moveTrack(playlistId: Long, trackId: Long, delta: Int) {
        db.withTransaction {
            val tracks = db.tracks().list(playlistId).sortedBy { it.position }
            val index = tracks.indexOfFirst { it.id == trackId }
            val target = index + delta
            if (index < 0 || target !in tracks.indices) return@withTransaction
            val mutable = tracks.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(target, item)
            mutable.forEachIndexed { position, track ->
                if (track.position != position) db.tracks().updatePosition(track.id, position)
            }
        }
    }

    suspend fun scoreMap(uris: List<String>): Map<String, Float> {
        if (uris.isEmpty()) return emptyMap()
        return db.energy().getMany(uris)
            .filter { it.status == STATUS_OK }
            .associate { it.uri to it.score }
    }

    suspend fun energyMap(uris: List<String>): Map<String, EnergyEntity> {
        if (uris.isEmpty()) return emptyMap()
        return db.energy().getMany(uris).associateBy { it.uri }
    }

    fun kickAnalysis() {
        scope.launch { analyzePending() }
    }

    private suspend fun insertScanned(playlistId: Long, scanned: List<ScannedTrack>): Int {
        if (scanned.isEmpty()) return 0
        return db.withTransaction {
            val existing = db.tracks().list(playlistId).map { it.uri }.toSet()
            var position = db.tracks().maxPosition(playlistId)
            val fresh = scanned.filter { it.uri.toString() !in existing }.map { item ->
                position += 1
                TrackEntity(
                    playlistId = playlistId,
                    uri = item.uri.toString(),
                    treeUri = item.treeUri?.toString(),
                    fileName = item.tags.fileName,
                    title = item.tags.title,
                    artist = item.tags.artist,
                    album = item.tags.album,
                    durationMs = item.tags.durationMs,
                    mime = item.tags.mime,
                    bitrateBps = item.tags.bitrateBps,
                    sampleRate = item.tags.sampleRate,
                    position = position,
                )
            }
            if (fresh.isNotEmpty()) db.tracks().insert(fresh)
            fresh.size
        }
    }

    private suspend fun analyzePending() {
        gate.withLock {
            val uris = db.tracks().distinctUris()
            if (uris.isEmpty()) {
                _progress.value = AnalysisProgress(0, 0, false)
                return
            }
            val pending = uris.filter { db.energy().get(it)?.status == null }
            if (pending.isEmpty()) {
                _progress.value = AnalysisProgress(uris.size, uris.size, false)
                return
            }
            var done = uris.size - pending.size
            _progress.value = AnalysisProgress(done, uris.size, true)
            for (uri in pending) {
                val features = runCatching { EnergyAnalyzer.analyze(context, Uri.parse(uri)) }
                val entity = features.fold(
                    onSuccess = { value ->
                        EnergyEntity(
                            uri = uri,
                            rms = value.rms,
                            onsetPerSec = value.onsetPerSec,
                            bpm = value.bpm,
                            bpmConfidence = value.bpmConfidence,
                            score = value.score,
                            status = STATUS_OK,
                            analyzedAt = System.currentTimeMillis(),
                        )
                    },
                    onFailure = { error ->
                        Log.w(TAG, "能量分析失败 $uri", error)
                        EnergyEntity(
                            uri = uri,
                            rms = 0f,
                            onsetPerSec = 0f,
                            bpm = 0f,
                            bpmConfidence = 0f,
                            score = 0f,
                            status = STATUS_FAILED,
                            analyzedAt = System.currentTimeMillis(),
                        )
                    },
                )
                db.energy().upsert(entity)
                done += 1
                _progress.value = AnalysisProgress(done, uris.size, true)
            }
            _progress.value = AnalysisProgress(uris.size, uris.size, false)
            _batchComplete.tryEmit(Unit)
        }
    }

    private fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
        private const val TAG = "EnergyAnalyzer"
    }
}
