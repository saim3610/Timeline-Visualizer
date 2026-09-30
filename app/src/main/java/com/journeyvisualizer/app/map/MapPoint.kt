package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.data.geo.ResolvedLocation

/**
 * One timeline event as the map sees it (Phase 4).
 *
 * [index] is the stable index into the timeline's resolved-point list — the
 * same index the timeline list uses, so marker taps and row taps always agree.
 * [lat]/[lng] are ALWAYS the original Timeline coordinates; [location] is
 * Phase 3 metadata enrichment only and never replaces them.
 */
data class MapPoint(
    val index: Int,
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
    val location: ResolvedLocation?,
) {
    /** Usable on a Web-Mercator map. Invalid points are listed, not drawn. */
    val isMappable: Boolean
        get() = lat.isFinite() && lng.isFinite() &&
            lat in -85.0511..85.0511 && lng in -180.0..180.0
}

/** Camera snapshot for Phase 5's animation engine to build on. */
data class MapCameraState(
    val lat: Double,
    val lng: Double,
    val zoom: Double,
)
