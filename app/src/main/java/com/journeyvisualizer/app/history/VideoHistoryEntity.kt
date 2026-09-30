package com.journeyvisualizer.app.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one generated video.
 *
 * Stores metadata and *references* only. The MP4 itself lives in
 * MediaStore (referenced by [contentUri] / [mediaStoreId]) and the
 * thumbnail is a small JPEG in the app cache ([thumbnailPath]).
 * Nothing here is uploaded anywhere.
 */
@Entity(tableName = "video_history")
data class VideoHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long?,
    /** Stable content:// or file:// URI string of the actual MP4. */
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "original_file_name")
    val originalFileName: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "modified_at_ms")
    val modifiedAtMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "width")
    val width: Int,
    @ColumnInfo(name = "height")
    val height: Int,
    @ColumnInfo(name = "fps")
    val fps: Int,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "aspect_ratio")
    val aspectRatio: String,
    @ColumnInfo(name = "map_style")
    val mapStyle: String?,
    @ColumnInfo(name = "timeline_start_ms")
    val timelineStartMs: Long?,
    @ColumnInfo(name = "timeline_end_ms")
    val timelineEndMs: Long?,
    @ColumnInfo(name = "event_count")
    val eventCount: Int,
    @ColumnInfo(name = "start_location")
    val startLocation: String?,
    @ColumnInfo(name = "end_location")
    val endLocation: String?,
    @ColumnInfo(name = "status")
    val status: String,
    /** Cache-dir path of the thumbnail JPEG, or null. */
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String?,
) {
    fun toItem(available: Boolean = true) = VideoHistoryItem(
        id = id,
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        originalFileName = originalFileName,
        createdAtMs = createdAtMs,
        modifiedAtMs = modifiedAtMs,
        durationMs = durationMs,
        width = width,
        height = height,
        fps = fps,
        sizeBytes = sizeBytes,
        aspectRatio = aspectRatio,
        mapStyle = mapStyle,
        timelineStartMs = timelineStartMs,
        timelineEndMs = timelineEndMs,
        eventCount = eventCount,
        startLocation = startLocation,
        endLocation = endLocation,
        thumbnailPath = thumbnailPath,
        isAvailable = available,
    )

    companion object {
        fun fromItem(item: VideoHistoryItem) = VideoHistoryEntity(
            id = item.id,
            mediaStoreId = item.mediaStoreId,
            contentUri = item.contentUri,
            displayName = item.displayName,
            originalFileName = item.originalFileName,
            createdAtMs = item.createdAtMs,
            modifiedAtMs = item.modifiedAtMs,
            durationMs = item.durationMs,
            width = item.width,
            height = item.height,
            fps = item.fps,
            sizeBytes = item.sizeBytes,
            aspectRatio = item.aspectRatio,
            mapStyle = item.mapStyle,
            timelineStartMs = item.timelineStartMs,
            timelineEndMs = item.timelineEndMs,
            eventCount = item.eventCount,
            startLocation = item.startLocation,
            endLocation = item.endLocation,
            status = VideoHistoryStatus.COMPLETED.name,
            thumbnailPath = item.thumbnailPath,
        )
    }
}
