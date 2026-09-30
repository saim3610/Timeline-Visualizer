package com.journeyvisualizer.app.animation

/**
 * Tuning for the animation timeline (Phase 5).
 *
 * The mapping real-world duration → animation duration is deterministic:
 * the same timeline and config always produce the same segment durations,
 * which is what lets a future video renderer reproduce playback exactly.
 */
data class AnimationConfig(
    /**
     * Soft target for the whole journey's animation length at 1x speed, in
     * ms. Per-segment min/max clamping takes precedence, so the real total
     * may come out shorter (never fabricated longer).
     */
    val targetTotalMs: Long = 90_000L,
    /** Shortest any travel segment may animate (avoids invisible hops). */
    val minSegmentMs: Long = 600L,
    /** Longest any travel segment may animate (avoids "frozen" gaps). */
    val maxSegmentMs: Long = 8_000L,
    /** Animation time spent on a zero-distance (dwell) event. */
    val dwellMs: Long = 1_200L,
    /** Below this distance two coordinates count as "the same place". */
    val zeroDistanceM: Double = 1.0,
    /** Speeds offered in the UI; applied as a pure time multiplier. */
    val speeds: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0),
    val defaultSpeed: Double = 1.0,
) {
    init {
        require(targetTotalMs > 0) { "targetTotalMs must be positive" }
        require(minSegmentMs > 0) { "minSegmentMs must be positive" }
        require(maxSegmentMs >= minSegmentMs) { "maxSegmentMs must be >= minSegmentMs" }
        require(dwellMs in minSegmentMs..maxSegmentMs) {
            "dwellMs must sit inside [minSegmentMs, maxSegmentMs]"
        }
        require(speeds.isNotEmpty() && speeds.all { it > 0 }) {
            "speeds must be non-empty and positive"
        }
        require(defaultSpeed in speeds) { "defaultSpeed must be one of speeds" }
    }
}
