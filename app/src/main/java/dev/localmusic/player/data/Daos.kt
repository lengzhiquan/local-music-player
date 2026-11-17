package dev.localmusic.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt,
               (SELECT COUNT(*) FROM tracks t WHERE t.playlistId = p.id) AS trackCount
        FROM playlists p
        ORDER BY p.createdAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistWithCount>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): PlaylistEntity?
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    fun observe(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    suspend fun list(playlistId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): TrackEntity?

    @Insert
    suspend fun insert(tracks: List<TrackEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tracks SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("SELECT DISTINCT uri FROM tracks")
    suspend fun distinctUris(): List<String>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TrackEntity?
}

@Dao
interface EnergyDao {
    @Query("SELECT * FROM energy WHERE uri = :uri")
    suspend fun get(uri: String): EnergyEntity?

    @Query("SELECT * FROM energy WHERE uri IN (:uris)")
    suspend fun getMany(uris: List<String>): List<EnergyEntity>

    @Query("SELECT * FROM energy")
    fun observeAll(): Flow<List<EnergyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EnergyEntity)
}
