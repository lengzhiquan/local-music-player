package dev.localmusic.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.localmusic.player.data.TrackEntity

fun TrackEntity.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { fileName })
                .setArtist(artist.ifBlank { "未知艺人" })
                .setAlbumTitle(album.ifBlank { "未知专辑" })
                .build(),
        )
        .build()
}
