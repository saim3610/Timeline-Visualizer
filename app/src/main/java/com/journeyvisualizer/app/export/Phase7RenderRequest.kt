package com.journeyvisualizer.app.export

import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.composition.ExportSpec

/**
 * Everything the Phase 7 renderer needs, assembled once by the UI layer.
 *
 * - [timeline]: the real Phase 5 [AnimationTimeline] (deterministic
 *   `stateAt` — the only animation source for both preview and export).
 * - [spec]: the Phase 6 [ExportSpec] — the same spec the preview dialog
 *   displays. Preview and export share this single source of truth; there
 *   is no separate export-side animation, camera, route-progress, or
 *   timestamp algorithm.
 * - [timelineRef]: the timeline file-name reference (metadata only —
 *   the JSON is never re-read or uploaded).
 */
data class Phase7RenderRequest(
    val timeline: AnimationTimeline,
    val spec: ExportSpec,
    val timelineRef: String,
) {
    /**
     * Identity key for duplicate-export protection: two requests with the
     * same key describe the same video. Compared in-process while a render
     * is active so an identical export cannot start twice.
     */
    val identityKey: String =
        "${spec.exportWidth}x${spec.exportHeight}@${spec.fps}/" +
            "${spec.totalVideoMs}ms/${spec.hashCode().toString(16)}"
}
