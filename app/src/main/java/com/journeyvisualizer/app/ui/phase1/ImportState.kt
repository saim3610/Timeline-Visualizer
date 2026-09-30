package com.journeyvisualizer.app.ui.phase1

import com.journeyvisualizer.app.data.FailureReason
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.ui.phase1.components.MapPin

/**
 * Real stages of the Phase 2/3 import pipeline.
 *
 * Each stage represents genuinely completed work — the Processing screen
 * advances only when a stage finishes, never on a fake timer.
 */
enum class ImportStage {
    READING,
    DETECTING,
    PARSING,
    RESOLVING,
    FINALIZING,
}

/** Real, parsed metadata about an imported Timeline file. */
data class ImportSummary(
    val fileName: String,
    val sizeLabel: String,
    /** Top-level entries found in the file (records, or segments for semantic exports). */
    val recordCount: Int,
    val isSegmentFormat: Boolean,
    val validPoints: Int,
    val invalidPoints: Int,
    val dateRangeLabel: String,
    /** The normalized journey; kept so later screens never reparse. */
    val journey: Journey,
    /**
     * Every Timeline point paired with its resolved location metadata.
     * Raw coordinates are preserved on each [ResolvedPoint.point]; the
     * [ResolvedPoint.location] carries the human-readable geography.
     * This is the handoff the Phase 4 map engine will consume.
     */
    val resolvedPoints: List<ResolvedPoint>,
    /** Honest preview pins derived from real coordinates (no invented names). */
    val pins: List<MapPin>,
    val warnings: List<String>,
) {
    /** Points with a CLOSE or APPROXIMATE location match. */
    val resolvedCount: Int get() = resolvedPoints.count { it.location.isResolved }

    /** Points where no GeoNames place was within range. */
    val unresolvedCount: Int get() = resolvedPoints.size - resolvedCount
}

/**
 * State of the real Timeline import.
 *
 * Held by [Phase1ViewModel] so the parsed result survives navigation
 * between Upload → Processing → Summary → Preview → Customize without
 * ever re-reading or re-parsing the file.
 */
sealed interface ImportState {
    data object Idle : ImportState

    data class Reading(
        val fileName: String,
        val sizeLabel: String?,
    ) : ImportState

    data class Parsing(
        val fileName: String,
        val stage: ImportStage,
        /** 0..1 overall progress derived from completed stages + parser progress. */
        val progress: Float,
    ) : ImportState

    data class Ready(val summary: ImportSummary) : ImportState

    data class Failed(
        val fileName: String?,
        val reason: FailureReason,
        /** Short, user-safe detail line (never raw JSON or coordinates). */
        val detail: String,
    ) : ImportState
}
