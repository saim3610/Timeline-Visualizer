package com.journeyvisualizer.app.history

/**
 * Domain model for one generated video in My Videos history.
 *
 * The MP4 itself is referenced, never embedded: [contentUri] is the stable
 * MediaStore content URI (API 29+) or a `file://` URI on older devices.
 * The database must never store the video binary — only this metadata.
 *
 * Timeline-derived fields (date range, event count, locations, map style)
 * come from the Phase 3/5/6 render inputs, never from the filename.
 */
data class VideoHistoryItem(
    /** Stable local ID (UUID). Survives renames and MediaStore re-indexing. */
    val id: String,
    /** MediaStore row id, when the video lives in MediaStore. */
    val mediaStoreId: Long?,
    /** Stable content/file URI string of the actual MP4. */
    val contentUri: String,
    /** User-facing display name (renameable; defaults to the file name). */
    val displayName: String,
    /** File name at export time, e.g. "Timeline_2026-09-30_1430.mp4". */
    val originalFileName: String,
    /** When the video was rendered (ms since epoch). */
    val createdAtMs: Long,
    /** Last metadata touch (rename etc.), ms since epoch. */
    val modifiedAtMs: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sizeBytes: Long,
    /** Canonical "16:9" / "9:16" / "1:1" label. */
    val aspectRatio: String,
    /** Map style label at render time, e.g. "Standard". */
    val mapStyle: String?,
    /** First timeline timestamp, or null when unavailable. */
    val timelineStartMs: Long?,
    /** Last timeline timestamp, or null when unavailable. */
    val timelineEndMs: Long?,
    val eventCount: Int,
    /** Resolved start city, or null — never invented. */
    val startLocation: String?,
    /** Resolved end city, or null — never invented. */
    val endLocation: String?,
    /** Disk path of the cached thumbnail, or null when none was made. */
    val thumbnailPath: String?,
    /** True when the referenced video is currently reachable. */
    val isAvailable: Boolean = true,
) {
    /** "Lahore → Skardu", or null when either end is unknown. */
    val routeLabel: String?
        get() = if (!startLocation.isNullOrBlank() && !endLocation.isNullOrBlank()) {
            "$startLocation → $endLocation"
        } else {
            null
        }

    /** "1080p" style label from the export height. */
    val resolutionLabel: String get() = "${height.coerceAtLeast(1)}p"
}

/** Render lifecycle for a history record. Only completed videos are listed. */
enum class VideoHistoryStatus {
    /** Successfully finalized and verified. The only listed status. */
    COMPLETED,
}
