package com.journeyvisualizer.app.map

/**
 * UI-agnostic control surface for the interactive timeline map (Phase 4).
 *
 * Implemented by [OsmMapController] (osmdroid). Phase 5's animation engine
 * should program against this interface — never against the MapView — so the
 * renderer stays swappable and the animation logic stays testable.
 *
 * All methods are safe to call before the view is attached; they are applied
 * when the MapView becomes available.
 */
interface InteractiveMapController {

    /** Switch the visible basemap. Only [BasemapStyle] values are accepted. */
    fun setMapStyle(style: BasemapStyle)

    /**
     * Show the journey: clustered markers + chronological route polyline +
     * start/end markers. Replaces any previous route.
     */
    fun setRoute(points: List<MapPoint>)

    /** Remove markers and route; keep the basemap. */
    fun clearRoute()

    /**
     * Highlight a timeline point (selection ring) and smoothly move the
     * camera to it. Null clears the highlight.
     */
    fun setSelectedPoint(index: Int?)

    /** Smoothly move the camera to a point, optionally changing zoom. */
    fun moveCameraToPoint(index: Int, zoom: Double? = null)

    /** Fit the camera to all mappable points, with padding. */
    fun fitTimelineBounds(animated: Boolean = true)

    /** Current camera, for Phase 5 to read and drive. */
    fun getCurrentCameraState(): MapCameraState

    // ------------------------------------------------------------------
    // Animation surface (Phase 5). The AnimationEngine drives these; the
    // engine never touches the MapView itself.
    // ------------------------------------------------------------------

    /** FULL shows the whole route; PROGRESSIVE emphasizes traveled portion. */
    fun setRouteDrawMode(mode: RouteDrawMode)

    /**
     * Show (or move) the animated position marker — visually distinct from
     * the static timeline dots. Safe to call every frame.
     */
    fun setAnimatedMarkerPosition(lat: Double, lng: Double)

    /** Hide the animated position marker. */
    fun hideAnimatedMarker()

    /**
     * Progressive route drawing: the first [traveledPointCount] route points
     * are fully traveled and the line extends to ([lat], [lng]). Incremental
     * internally — cheap to call every frame. Honored in PROGRESSIVE mode.
     */
    fun setAnimatedRouteProgress(traveledPointCount: Int, lat: Double, lng: Double)

    /**
     * Throttled camera follow for the animated marker: recenters only when
     * the marker drifts out of the central viewport area, so the camera
     * never jumps every frame.
     */
    fun followAnimatedMarker(lat: Double, lng: Double)

    /** Immediately glide the camera to a coordinate (explicit recenter). */
    fun recenterOn(lat: Double, lng: Double)

    /** Fired when the user starts a touch gesture on the map. */
    fun setOnUserInteractionListener(listener: (() -> Unit)?)

    /** Fired with the stable timeline index when a marker is tapped. */
    fun setOnMarkerClickListener(listener: ((index: Int) -> Unit)?)

    // ------------------------------------------------------------------
    // Video composition surface (Phase 6). Default no-ops so existing
    // implementers are unaffected; OsmMapController overrides them.
    // ------------------------------------------------------------------

    /**
     * Tune the follow camera: recenter when the marker leaves
     * [viewportFraction] of the viewport height, at most every [throttleMs].
     * Used by the composition camera modes (follow vs. smart follow).
     */
    fun setFollowTuning(viewportFraction: Float, throttleMs: Long) {}

    /** Change the animated position marker's rendering (Phase 5 marker). */
    fun setAnimatedMarkerStyle(style: VideoMarkerStyle) {}

    /**
     * Route visibility/width/opacity for video composition. Applies to the
     * full route and the progressive traveled overlay. Only values the
     * provider can render reliably are honored.
     */
    fun setRouteAppearance(visible: Boolean, widthScale: Float, opacity: Float) {}

    /** Show/hide the S/E start/end badges (real first/last coordinates). */
    fun setStartEndMarkersVisible(visible: Boolean) {}

    /** Release the MapView. After this the controller accepts a new attach. */
    fun detach()

    /**
     * Forward the host lifecycle so tile loading pauses/resumes correctly.
     * Called from the MapView's ON_RESUME / ON_PAUSE.
     */
    fun onResumeView()
    fun onPauseView()
}
