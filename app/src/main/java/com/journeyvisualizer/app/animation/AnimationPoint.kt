package com.journeyvisualizer.app.animation

import com.journeyvisualizer.app.data.geo.ResolvedPoint

/**
 * One animation-ready timeline event (Phase 5).
 *
 * Animation data, not a replacement for timeline data: [source] keeps the
 * original [ResolvedPoint] (raw coordinates + timestamp + GeoNames metadata)
 * reachable at all times. [id] is the stable timeline index — selection and
 * seeking use it, never city names.
 */
data class AnimationPoint(
    /** Stable index into the original resolved-point list. */
    val id: Int,
    /** Original Timeline latitude — never a GeoNames city center. */
    val lat: Double,
    /** Original Timeline longitude — never a GeoNames city center. */
    val lng: Double,
    /** Normalized Timeline timestamp (ms since epoch). */
    val timestampMs: Long,
    /** Resolved place name, or null when unresolved. */
    val city: String?,
    /** Resolved country name, or null when unresolved. */
    val country: String?,
    /** Whether GeoNames produced a usable match for this coordinate. */
    val isResolved: Boolean,
    /** The original timeline event this animation point was derived from. */
    val source: ResolvedPoint,
) {
    /** Human label for overlays; honest about unresolved points. */
    val displayName: String get() = city ?: "Location unavailable"
}
