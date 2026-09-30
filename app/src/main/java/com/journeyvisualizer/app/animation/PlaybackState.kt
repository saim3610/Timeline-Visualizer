package com.journeyvisualizer.app.animation

/**
 * Explicit animation states (Phase 5). The [AnimationEngine] is the single
 * source of truth — the UI never keeps a competing playback state.
 */
enum class PlaybackState {
    /** Built and ready, playback never started. */
    IDLE,
    /** Timeline is being prepared on a background thread. */
    PREPARING,
    /** Clock advancing; marker, route, camera and timestamp move. */
    PLAYING,
    /** Frozen mid-journey; resume continues from the same position. */
    PAUSED,
    /** Reached the end; marker sits at the final coordinate. */
    COMPLETED,
    /** Reset to the beginning by the user. */
    STOPPED,
    /** Something failed (e.g. no animatable points). Holds position. */
    ERROR,
}

/** Camera behavior during playback. */
enum class CameraFollowMode {
    /** Camera tracks the animated marker (suspended while the user pans). */
    FOLLOW,
    /** User pans/zooms freely; animation continues underneath. */
    FREE,
}

/**
 * One deterministic animation frame (Phase 5).
 *
 * Pure function output of [AnimationTimeline.stateAt]: given an animation
 * clock value it always yields the same frame. A future video renderer can
 * drive the whole visualization through this — marker position, route
 * progress, displayed timestamp and current event — without any wall clock.
 */
data class AnimationFrameState(
    /** Animation-clock position this frame was computed for (ms). */
    val positionMs: Long,
    /** Total animation length at 1x (ms). */
    val totalMs: Long,
    /** Index into [AnimationTimeline.segments]; -1 when there are no segments. */
    val segmentIndex: Int,
    /** 0..1 progress inside the current segment. */
    val fraction: Double,
    /** Interpolated marker latitude (slerp; exact endpoints at 0/1). */
    val lat: Double,
    /** Interpolated marker longitude. */
    val lng: Double,
    /**
     * Displayed timestamp: linearly interpolated between the segment's real
     * timestamps. Display only — original event records are untouched.
     */
    val displayedTimestampMs: Long,
    /** Stable timeline id of the event this frame belongs to. */
    val eventId: Int,
    /** The event the marker is currently at (or dwelling on). */
    val currentPoint: AnimationPoint,
    /** The event the marker is traveling toward; null at the journey end. */
    val nextPoint: AnimationPoint?,
    /** Number of fully-traveled points (for progressive route drawing). */
    val traveledPointCount: Int,
    /** 0..1 overall journey progress (for the scrubber). */
    val overallFraction: Double,
)
