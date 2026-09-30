package com.journeyvisualizer.app.export

import android.net.Uri

/**
 * Explicit render lifecycle (Phase 7).
 *
 * The exporter moves strictly forward: IDLE → PREPARING → RENDERING →
 * FINALIZING → COMPLETED, with FAILED or CANCELLED reachable from any
 * active state. There is intentionally no PAUSED state — pausing a
 * hardware-encoder drain loop cannot be done safely, so cancellation is
 * the only way to stop a running render.
 */
sealed interface RenderState {
    data object Idle : RenderState
    data object Preparing : RenderState
    data object Rendering : RenderState
    data object Finalizing : RenderState

    data class Completed(
        val uri: Uri,
        val fileName: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val fps: Int,
        val sizeBytes: Long,
        /** Phase 8 history record id, when registration succeeded. */
        val historyId: String? = null,
    ) : RenderState

    data class Failed(
        /** Human-readable, safe to show in the UI. */
        val message: String,
        /** Technical detail for logs only; null when there is none. */
        val debugDetail: String? = null,
    ) : RenderState

    data object Cancelled : RenderState
}

/**
 * Real rendering progress — always derived from completed frames, never
 * from timers or estimates.
 */
data class RenderProgress(
    /** Frames fully encoded so far. */
    val framesDone: Int,
    /** Total frames for this render. */
    val totalFrames: Int,
    /** Sustained encode rate over the recent window (frames/sec), 0 when unknown. */
    val framesPerSecond: Double = 0.0,
) {
    /** 0..1 fraction complete. */
    val fraction: Float get() =
        if (totalFrames <= 0) 0f else (framesDone.toFloat() / totalFrames).coerceIn(0f, 1f)

    /**
     * Estimated milliseconds remaining, or null when it cannot be
     * computed reliably (no rate measurement yet).
     */
    val etaMs: Long? get() {
        if (framesPerSecond <= 0.0 || framesDone >= totalFrames) return null
        return ((totalFrames - framesDone) / framesPerSecond * 1000).toLong()
    }
}
