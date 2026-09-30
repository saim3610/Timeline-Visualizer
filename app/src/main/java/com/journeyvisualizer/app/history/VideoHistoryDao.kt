package com.journeyvisualizer.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Data access for the local video-history table. UI never touches this directly. */
@Dao
interface VideoHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(entity: VideoHistoryEntity)

    @Query("SELECT * FROM video_history ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<VideoHistoryEntity>>

    @Query("SELECT * FROM video_history ORDER BY created_at_ms DESC")
    suspend fun getAllVideos(): List<VideoHistoryEntity>

    @Query("SELECT * FROM video_history WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<VideoHistoryEntity?>

    @Query("SELECT * FROM video_history WHERE id = :id LIMIT 1")
    suspend fun getVideoById(id: String): VideoHistoryEntity?

    @Query(
        "UPDATE video_history SET display_name = :name, modified_at_ms = :modifiedMs " +
            "WHERE id = :id",
    )
    suspend fun renameVideo(id: String, name: String, modifiedMs: Long)

    @Query(
        "UPDATE video_history SET thumbnail_path = :path WHERE id = :id",
    )
    suspend fun setThumbnailPath(id: String, path: String?)

    @Query("DELETE FROM video_history WHERE id = :id")
    suspend fun deleteVideo(id: String)

    @Query("DELETE FROM video_history")
    suspend fun deleteAllVideos()

    @Query("SELECT COUNT(*) FROM video_history")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM video_history WHERE " +
            "display_name LIKE '%' || :q || '%' ESCAPE '\\' OR " +
            "start_location LIKE '%' || :q || '%' ESCAPE '\\' OR " +
            "end_location LIKE '%' || :q || '%' ESCAPE '\\' " +
            "ORDER BY created_at_ms DESC",
    )
    suspend fun search(q: String): List<VideoHistoryEntity>
}
