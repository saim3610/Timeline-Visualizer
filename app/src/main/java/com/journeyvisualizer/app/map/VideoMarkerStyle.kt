package com.journeyvisualizer.app.map

/**
 * Animated position marker styles (Phase 6 video composition).
 *
 * All variants stay visually compatible with Phase 5's animated marker
 * (the blue "you are here" dot) — they only change its rendering, never
 * create a separate marker system.
 */
enum class VideoMarkerStyle {
    /** The standard blue dot (Phase 5 default). */
    STANDARD,
    /** Smaller, quieter dot. */
    MINIMAL_DOT,
    /** Larger dot with a highlight ring. */
    HIGHLIGHTED,
    /** No animated marker; the journey reads from the route alone. */
    HIDDEN,
}
