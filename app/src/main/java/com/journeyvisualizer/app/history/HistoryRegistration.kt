package com.journeyvisualizer.app.history

import android.net.Uri
import com.journeyvisualizer.app.export.OutputValidator
import com.journeyvisualizer.app.export.Phase7RenderRequest
import java.util.UUID

/**
 * Builds the history record for a freshly finalized Phase 7 export.
 *
 * Called only after the MP4 has been verified ([OutputValidator]).
 * Failed/cancelled/corrupt renders never reach this code — no temporary
 * render records are kept, so there is nothing to mark or clean up later.
 *
 * Metadata comes from the render inputs themselves (timeline, spec) and
 * the validated output — never re-derived from the filename, and location
 * names come from the existing Phase 3 resolution results on the
 * animation points (never invented, null when unresolved).
 */
object HistoryRegistration {

    fun build(
        request: Phase7RenderRequest,
        info: OutputValidator.Info,
        outUri: Uri,
        fileName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): VideoHistoryItem {
        val spec = request.spec
        val points = request.timeline.points
        val first = points.firstOrNull()
        val last = points.lastOrNull()

        val mediaStoreId: Long? = outUri.lastPathSegment?.toLongOrNull()
            .takeIf { outUri.scheme == "content" }

        return VideoHistoryItem(
            id = UUID.randomUUID().toString(),
            mediaStoreId = mediaStoreId,
            contentUri = outUri.toString(),
            displayName = RenameValidator.defaultDisplayName(fileName),
            originalFileName = fileName,
            createdAtMs = nowMs,
            modifiedAtMs = nowMs,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            fps = spec.fps,
            sizeBytes = info.sizeBytes,
            aspectRatio = HistoryLabels.aspectLabel(spec.exportWidth, spec.exportHeight),
            mapStyle = HistoryLabels.mapStyleLabel(spec.mapStyle),
            timelineStartMs = first?.timestampMs,
            timelineEndMs = last?.timestampMs,
            eventCount = spec.eventCount,
            startLocation = first?.city,
            endLocation = last?.city,
            thumbnailPath = null,
        )
    }
}
