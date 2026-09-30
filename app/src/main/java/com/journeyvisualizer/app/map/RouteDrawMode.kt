package com.journeyvisualizer.app.map

/**
 * How the journey route is drawn during animation (Phase 5).
 */
enum class RouteDrawMode {
    /** The entire timeline route is shown at full emphasis. */
    FULL,

    /**
     * The full route is dimmed and only the already-traveled portion is
     * emphasized, growing behind the animated marker.
     */
    PROGRESSIVE,
}
