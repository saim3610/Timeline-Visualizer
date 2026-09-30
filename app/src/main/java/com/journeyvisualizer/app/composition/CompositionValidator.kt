package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.map.RouteDrawMode

/**
 * Validation for video composition (Phase 6).
 *
 * Runs before the user can continue toward export. Returns human-readable
 * issues; an empty list means the composition is internally consistent and
 * the timeline can back it. Never silently corrects settings — problems
 * are reported for the user to fix.
 */
data class CompositionIssue(
    /** Machine-readable field key, e.g. "timeline", "title", "duration". */
    val field: String,
    val message: String,
)

object CompositionValidator {

    const val MAX_TITLE_LEN = 80
    const val MAX_SUBTITLE_LEN = 120
    const val MIN_CUSTOM_SEC = 10
    const val MAX_CUSTOM_SEC = 600

    fun validate(
        composition: VideoComposition,
        hasTimeline: Boolean,
        animationTotalMs: Long,
    ): List<CompositionIssue> {
        val issues = ArrayList<CompositionIssue>()

        if (!hasTimeline) {
            issues += CompositionIssue(
                "timeline",
                "No timeline is loaded. Import a Timeline JSON file first.",
            )
            // Without a timeline the remaining checks are meaningless.
            return issues
        }
        if (animationTotalMs <= 0) {
            issues += CompositionIssue(
                "timeline",
                "The timeline has no animatable points. The animation engine cannot generate a journey.",
            )
        }

        if (composition.durationMode == VideoDurationMode.CUSTOM &&
            composition.customDurationSec !in MIN_CUSTOM_SEC..MAX_CUSTOM_SEC
        ) {
            issues += CompositionIssue(
                "duration",
                "Custom duration must be between $MIN_CUSTOM_SEC and $MAX_CUSTOM_SEC seconds.",
            )
        }

        if (composition.title.length > MAX_TITLE_LEN) {
            issues += CompositionIssue(
                "title",
                "The title is too long — keep it under $MAX_TITLE_LEN characters.",
            )
        }
        if (composition.subtitle.length > MAX_SUBTITLE_LEN) {
            issues += CompositionIssue(
                "subtitle",
                "The subtitle is too long — keep it under $MAX_SUBTITLE_LEN characters.",
            )
        }

        if (composition.introEnabled && composition.introDurationSec !in 1..5) {
            issues += CompositionIssue("intro", "Intro duration must be 1–5 seconds.")
        }
        if (composition.outroEnabled && composition.outroDurationSec !in 1..5) {
            issues += CompositionIssue("outro", "Outro duration must be 1–5 seconds.")
        }

        if (!composition.routeVisible && composition.routeDrawMode == RouteDrawMode.PROGRESSIVE) {
            // Not an error — progressive mode with a hidden route is just
            // the moving marker. No issue; documented behavior.
        }

        return issues
    }
}
