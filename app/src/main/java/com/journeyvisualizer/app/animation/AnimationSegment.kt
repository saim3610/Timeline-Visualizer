package com.journeyvisualizer.app.animation

/**
 * One leg of the animated journey: [from] → [to] (Phase 5).
 *
 * [realDurationMs] is the true Timeline elapsed time (never distorted);
 * [animDurationMs] is the compressed playback length at 1x speed, derived
 * deterministically from the real duration and the [AnimationConfig].
 * [startAnimMs]/[endAnimMs] are cumulative animation-clock positions used
 * for O(log n) seeking.
 */
data class AnimationSegment(
    val from: AnimationPoint,
    val to: AnimationPoint,
    /** True elapsed time between the two Timeline timestamps (>= 0). */
    val realDurationMs: Long,
    /** Compressed playback duration at 1x speed. */
    val animDurationMs: Long,
    /** Great-circle distance in meters. */
    val distanceM: Double,
    /** True when the two endpoints are the same place: time passes, the marker stays. */
    val isDwell: Boolean,
    /** Animation-clock position where this segment starts. */
    val startAnimMs: Long,
    /** Animation-clock position where this segment ends. */
    val endAnimMs: Long,
) {
    init {
        require(realDurationMs >= 0) { "realDurationMs must be >= 0" }
        require(animDurationMs > 0) { "animDurationMs must be > 0" }
        require(endAnimMs >= startAnimMs) { "endAnimMs must be >= startAnimMs" }
    }
}
