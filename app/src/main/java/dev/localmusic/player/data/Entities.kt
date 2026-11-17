package dev.localmusic.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index(value = ["playlistId", "uri"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uri: String,
    val treeUri: String?,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val bitrateBps: Int?,
    val sampleRate: Int?,
    val position: Int,
)

@Entity(tableName = "energy")
data class EnergyEntity(
    @PrimaryKey val uri: String,
    val rms: Float,
    val onsetPerSec: Float,
    val bpm: Float,
    val bpmConfidence: Float,
    val score: Float,
    val status: String,
    val analyzedAt: Long,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
)
