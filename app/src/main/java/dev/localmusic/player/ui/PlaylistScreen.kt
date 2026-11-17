package dev.localmusic.player.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localmusic.player.R
import dev.localmusic.player.data.EnergyEntity
import dev.localmusic.player.data.MusicRepository
import dev.localmusic.player.data.PlaylistWithCount
import dev.localmusic.player.data.TrackEntity
import dev.localmusic.player.energy.EnergyGroup
import dev.localmusic.player.library.AudioMetadata
import dev.localmusic.player.library.TrackOrder
import dev.localmusic.player.library.TrackSort
import dev.localmusic.player.playback.PlayMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(
    playlistId: Long,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val playlists by vm.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val tracks by vm.tracks(playlistId).collectAsState(initial = emptyList())
    val mode by vm.mode.collectAsState()
    val progress by vm.progress.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanCount by vm.scanCount.collectAsState()
    val player by vm.player.collectAsState()
    val energyRows by vm.energies.collectAsState()
    val energyByUri = energyRows.associateBy { it.uri }
    val sort by vm.trackSort.collectAsState()
    var query by remember { mutableStateOf("") }
    val visible = remember(tracks, sort, query) { TrackOrder.arrange(tracks, sort, query) }
    val canReorder = sort == TrackSort.PLAYLIST && query.isBlank()
    val grouped = dev.localmusic.player.energy.EnergyMath.arrange(
        tracks,
        { track -> energyByUri[track.uri]?.takeIf { it.status == MusicRepository.STATUS_OK }?.score },
        kotlin.random.Random(1),
    ).associateBy { it.item.id }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addFolder(playlistId, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(playlistId, uris)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                playlist?.name ?: "播放列表",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                if (query.isBlank()) "${tracks.size} 首" else "${visible.size}/${tracks.size} 首",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("搜索歌名或歌手") },
                placeholder = { Text("输入歌名、歌手或拼音") },
            )
            Text(
                "排序",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackSort.entries.forEach { item ->
                    FilterChip(
                        selected = sort == item,
                        onClick = { vm.setTrackSort(item) },
                        label = { Text(item.label) },
                    )
                }
            }
            Row {
                TextButton(onClick = { folderPicker.launch(null) }) { Text("添加文件夹") }
                TextButton(onClick = {
                    filePicker.launch(
                        arrayOf(
                            "audio/*",
                            "audio/mpeg",
                            "audio/flac",
                            "audio/mp4",
                            "audio/ogg",
                            "audio/wav",
                            "audio/x-wav",
                            "audio/aac",
                        ),
                    )
                }) { Text("添加歌曲") }
                TextButton(
                    onClick = {
                        vm.playPlaylist(playlistId, ordered = visible)
                        onOpenPlayer()
                    },
                    enabled = visible.isNotEmpty(),
                ) { Text("播放") }
            }
            Text(
                "播放方式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { vm.setMode(item) },
                        label = { Text(item.label) },
                    )
                }
            }
            if (scanning) {
                Text(
                    if (scanCount > 0) "正在读取，已找到 $scanCount 首" else "正在读取文件夹",
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
            if (progress.running && progress.total > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "正在分析能量 ${progress.done}/${progress.total}",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { progress.done / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            if (tracks.isEmpty() && !scanning) {
                Text(
                    "还没有歌曲。添加文件夹后，同名的 .lrc 也会被认作歌词。",
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (tracks.isNotEmpty() && visible.isEmpty()) {
                Text(
                    "没有匹配的歌曲",
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(visible, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                index = index,
                count = visible.size,
                track = track,
                energyLabel = energyCaption(energyByUri[track.uri], grouped[track.id]?.group),
                active = player.mediaId == track.id.toString(),
                reorderEnabled = canReorder,
                onPlay = {
                    vm.playPlaylist(playlistId, track.id, ordered = visible)
                    onOpenPlayer()
                },
                onUp = { vm.moveTrack(playlistId, track, -1) },
                onDown = { vm.moveTrack(playlistId, track, 1) },
                onRemove = { vm.removeTrack(playlistId, track) },
            )
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    count: Int,
    track: TrackEntity,
    energyLabel: String?,
    active: Boolean,
    reorderEnabled: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            modifier = Modifier.width(28.dp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title.ifBlank { track.fileName },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val artist = track.artist.ifBlank { "未知艺人" }
            val tech = AudioMetadata.techLine(track.mime, track.fileName, track.bitrateBps, track.sampleRate)
            Text(
                "$artist · $tech",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!energyLabel.isNullOrBlank()) {
                Text(
                    energyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        if (reorderEnabled) {
            IconButton(onClick = onUp, enabled = index > 0) {
                Icon(painterResource(R.drawable.ic_up), contentDescription = "上移")
            }
            IconButton(onClick = onDown, enabled = index < count - 1) {
                Icon(painterResource(R.drawable.ic_down), contentDescription = "下移")
            }
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "移除")
        }
    }
}

fun energyCaption(entity: EnergyEntity?, group: EnergyGroup?): String? {
    if (entity == null) return null
    if (entity.status != MusicRepository.STATUS_OK || group == null || group == EnergyGroup.UNKNOWN) return null
    val name = if (group == EnergyGroup.CALM) "平静" else "高能"
    val bpm = if (entity.bpmConfidence >= 0.25f && entity.bpm > 0f) " · 约 ${entity.bpm.toInt()} BPM" else ""
    return "$name ${"%.2f".format(entity.score)}$bpm"
}
