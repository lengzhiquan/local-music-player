package dev.localmusic.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localmusic.player.R
import dev.localmusic.player.energy.EnergyGroup
import dev.localmusic.player.library.AudioMetadata
import dev.localmusic.player.lyrics.LyricLine
import dev.localmusic.player.lyrics.LyricOrigin
import dev.localmusic.player.lyrics.Lyrics
import dev.localmusic.player.lyrics.lyricTranslationY
import dev.localmusic.player.playback.PlayMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(vm: PlayerViewModel, onBack: () -> Unit, onOpenCompressor: () -> Unit) {
    val player by vm.player.collectAsState()
    val track by vm.currentTrack.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val lyricOrigin by vm.lyricOrigin.collectAsState()
    val lyricFetch by vm.lyricFetch.collectAsState()
    val autoLyrics by vm.autoLyrics.collectAsState()
    val art by vm.art.collectAsState()
    val mode by vm.mode.collectAsState()
    val arrangement by vm.arrangement.collectAsState()
    val progress by vm.progress.collectAsState()
    val scroll = rememberScrollState()

    val duration = when {
        player.durationMs > 0 -> player.durationMs
        track != null && track!!.durationMs > 0 -> track!!.durationMs
        else -> 0L
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shownPosition = if (dragging) dragValue else player.positionMs.toFloat()
    val grouped = arrangement.associateBy { it.item.id.toString() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            TextButton(onClick = onOpenCompressor) { Text("动态范围压缩") }
        }
        PlaybackStage(
            mediaId = player.mediaId,
            art = art,
            lyrics = lyrics,
            positionMs = shownPosition.toLong(),
            durationMs = duration,
            fetch = lyricFetch,
            onSearch = vm::searchLyrics,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            player.title.ifBlank { "没有正在播放的歌曲" },
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            player.artist.ifBlank { "未知艺人" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            player.album.ifBlank { "未知专辑" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (track != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                AudioMetadata.techLine(track!!.mime, track!!.fileName, track!!.bitrateBps, track!!.sampleRate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (player.buffering) {
            Text("正在缓冲", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = shownPosition.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                vm.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownPosition.toLong()), style = MaterialTheme.typography.bodyMedium)
            Text(formatTime(duration), style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::previous, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(R.drawable.ic_prev), contentDescription = "上一首", modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = vm::toggle,
                shape = RoundedCornerShape(40.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(76.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (player.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = vm::next, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(R.drawable.ic_next), contentDescription = "下一首", modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { vm.setMode(item) },
                    label = { Text(item.label) },
                )
            }
        }
        if (progress.running && progress.total > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "正在分析能量 ${progress.done}/${progress.total}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress.done / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        if (player.hasTrack) {
            Spacer(Modifier.height(18.dp))
            LyricsBlock(
                lyrics = lyrics,
                origin = lyricOrigin,
                auto = autoLyrics,
                onAutoChange = vm::setAutoLyrics,
            )
        }
        if (player.queue.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("播放队列", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            player.queue.forEachIndexed { index, entry ->
                val scored = grouped[entry.mediaId]
                val tag = when (scored?.group) {
                    EnergyGroup.CALM -> "平静 ${scored.score?.let { "%.2f".format(it) } ?: ""}".trim()
                    EnergyGroup.ENERGETIC -> "高能 ${scored.score?.let { "%.2f".format(it) } ?: ""}".trim()
                    else -> null
                }
                QueueRow(
                    index = index,
                    count = player.queue.size,
                    title = entry.title.ifBlank { "未命名" },
                    detail = listOfNotNull(entry.artist.ifBlank { null }, tag).joinToString(" · "),
                    current = entry.isCurrent,
                    onPlay = { vm.playQueueAt(index) },
                    onUp = { vm.moveQueue(index, -1) },
                    onDown = { vm.moveQueue(index, 1) },
                    onRemove = { vm.removeFromQueue(index) },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LyricsBlock(
    lyrics: dev.localmusic.player.lyrics.Lyrics?,
    origin: LyricOrigin?,
    auto: Boolean,
    onAutoChange: (Boolean) -> Unit,
) {
    if (lyrics != null && lyrics.hasText() && origin == LyricOrigin.CACHE) {
        Text(
            "来自 LRCLIB，已保存在本机",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("自动查找联网歌词", style = MaterialTheme.typography.bodyLarge)
            Text(
                "没有本地歌词时，按歌名和艺人查询 LRCLIB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = auto, onCheckedChange = onAutoChange)
    }
}

@Composable
private fun PlaybackStage(
    mediaId: String?,
    art: android.graphics.Bitmap?,
    lyrics: Lyrics?,
    positionMs: Long,
    durationMs: Long,
    fetch: LyricFetch,
    onSearch: () -> Unit,
) {
    val hasLyrics = lyrics?.hasText() == true
    var showArt by remember(mediaId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    val focus = when {
        lyrics == null || !hasLyrics -> -1
        lyrics.synced -> lyrics.currentIndex(positionMs)
        else -> lyrics.plainFocusIndex(positionMs, durationMs)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = hasLyrics) { showArt = !showArt },
    ) {
        if (!hasLyrics || showArt) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_note),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!hasLyrics) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) {
                    LyricStatus(fetch, onSearch)
                }
            }
        } else {
            LyricScroller(lyrics = lyrics!!, focus = focus, positionMs = positionMs)
        }
        if (hasLyrics) {
            Text(
                text = if (showArt) "歌词" else "封面",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            )
        }
    }
}

@Composable
private fun LyricStatus(fetch: LyricFetch, onSearch: () -> Unit) {
    when (fetch) {
        LyricFetch.Loading -> Text("正在查找联网歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LyricFetch.Empty -> TextButton(onClick = onSearch) { Text("没有找到，再试一次") }
        LyricFetch.Failed -> TextButton(onClick = onSearch) { Text("查找失败，请检查网络") }
        LyricFetch.Idle -> TextButton(onClick = onSearch) { Text("查找联网歌词") }
    }
}

@Composable
private fun LyricScroller(lyrics: Lyrics, focus: Int, positionMs: Long) {
    val slot = 52.dp
    val activeSlot = 72.dp
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().padding(horizontal = 16.dp)) {
        val density = LocalDensity.current
        val translation = with(density) {
            lyricTranslationY(
                activeIndex = focus,
                viewportPx = maxHeight.toPx(),
                linePx = slot.toPx(),
                activePx = activeSlot.toPx(),
            )
        }
        val shift by animateFloatAsState(
            targetValue = translation,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            label = "lyricScroll",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = shift },
        ) {
            lyrics.lines.forEachIndexed { index, line ->
                val current = index == focus
                Box(
                    modifier = Modifier.fillMaxWidth().height(if (current) activeSlot else slot),
                    contentAlignment = Alignment.Center,
                ) {
                    if (line.text.isNotBlank()) {
                        Text(
                            text = karaokeText(line, current, positionMs),
                            color = when {
                                current && !line.hasWordTiming() -> MaterialTheme.colorScheme.primary
                                current -> MaterialTheme.colorScheme.onSurface
                                focus >= 0 && index < focus -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                            fontSize = if (current) 24.sp else 16.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            lineHeight = if (current) 30.sp else 22.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun karaokeText(line: LyricLine, current: Boolean, positionMs: Long) =
    if (current && line.hasWordTiming()) {
        val sung = line.sungCharacters(positionMs).coerceIn(0, line.text.length)
        val sungColor = MaterialTheme.colorScheme.primary
        val restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        buildAnnotatedString {
            withStyle(SpanStyle(color = sungColor)) { append(line.text.take(sung).ifEmpty { "" }) }
            withStyle(SpanStyle(color = restColor)) { append(line.text.drop(sung)) }
        }
    } else {
        buildAnnotatedString { append(line.text.ifBlank { "···" }) }
    }

@Composable
private fun QueueRow(
    index: Int,
    count: Int,
    title: String,
    detail: String,
    current: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPlay)
                .padding(vertical = 6.dp),
        ) {
            Text(
                "${index + 1}  $title",
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onUp, enabled = index > 0) {
            Icon(painterResource(R.drawable.ic_up), contentDescription = "上移")
        }
        IconButton(onClick = onDown, enabled = index < count - 1) {
            Icon(painterResource(R.drawable.ic_down), contentDescription = "下移")
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "从队列移除")
        }
    }
}

@Composable
fun MiniPlayer(
    state: dev.localmusic.player.playback.NowPlaying,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.title.ifBlank { "正在播放" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.artist.ifBlank { "未知艺人" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                )
            }
        }
    }
}
