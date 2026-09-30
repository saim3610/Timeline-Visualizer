package com.journeyvisualizer.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

/**
 * osmdroid implementation of [InteractiveMapController] (Phase 4).
 *
 * - Basemap: [BasemapStyles] tile specs served through a URL-pattern tile
 *   source (supports Esri's z/y/x ordering, which XYTileSource cannot).
 * - Markers: lightweight grid clustering rebuilt on zoom changes — no extra
 *   clustering dependency, and the raw timeline list is never altered.
 * - Route: one chronological [Polyline]; display-decimated past 4k points.
 * - Start/end: distinct letter badges. Selection: highlight ring.
 *
 * All calls are safe before [attach]; pending state applies on attach.
 * Must be used from the main thread (it drives a View).
 */
class OsmMapController(private val appContext: Context) : InteractiveMapController {

    private var mapView: MapView? = null
    private var style: BasemapStyle = BasemapStyle.STANDARD
    private var points: List<MapPoint> = emptyList()
    private var selectedIndex: Int? = null
    private var markerListener: ((Int) -> Unit)? = null
    private var pendingFit = false

    private var routeLine: Polyline? = null
    private var labelOverlay: TilesOverlay? = null
    private val pointMarkers = mutableListOf<Marker>()
    private val markerIndex = HashMap<Marker, Int>()
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var highlightMarker: Marker? = null

    // -- Phase 5 animation overlays --------------------------------------
    private var routeDrawMode: RouteDrawMode = RouteDrawMode.PROGRESSIVE
    private var animatedMarker: Marker? = null
    private var traveledLine: Polyline? = null
    private val traveledGeo = ArrayList<GeoPoint>()
    private var lastTraveledCount: Int = -1
    private var pendingProgress: Triple<Int, Double, Double>? = null
    private var userInteractionListener: (() -> Unit)? = null
    private var lastFollowMs: Long = 0L

    // -- Phase 6 video composition ----------------------------------------
    private var followViewportFraction: Float = FOLLOW_VIEWPORT_FRACTION
    private var followThrottleMs: Long = FOLLOW_THROTTLE_MS
    private var animatedMarkerStyle: VideoMarkerStyle = VideoMarkerStyle.STANDARD
    private var animatedMarkerIconStyle: VideoMarkerStyle? = null
    private var routeVisible: Boolean = true
    private var routeWidthScale: Float = 1f
    private var routeOpacity: Float = 1f
    private var startEndMarkersVisible: Boolean = true

    private val zoomListener = object : MapListener {
        override fun onScroll(event: ScrollEvent?): Boolean = false
        override fun onZoom(event: ZoomEvent?): Boolean {
            rebuildPointMarkers()
            return false
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Bind to a MapView (call from AndroidView factory). */
    fun attach(view: MapView) {
        Configuration.getInstance().setUserAgentValue(appContext.packageName)
        mapView = view
        view.setTileSource(patternSource(BasemapStyles.specFor(style).base))
        view.setMultiTouchControls(true)
        view.overlays.add(RotationGestureOverlay(view))
        view.addMapListener(zoomListener)
        // User gestures suspend camera-follow; the listener only observes.
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                userInteractionListener?.invoke()
            }
            false
        }
        applyOverlayTiles()
        // Animation overlays are view-bound: rebuild them for the new view.
        animatedMarker = null
        animatedMarkerIconStyle = null
        traveledLine = null
        traveledGeo.clear()
        lastTraveledCount = -1
        rebuildRoute()
        rebuildPointMarkers()
        applySelection()
        applyRouteDrawMode()
        pendingProgress?.let { (count, lat, lng) ->
            pendingProgress = null
            setAnimatedRouteProgress(count, lat, lng)
        }
        if (pendingFit) {
            pendingFit = false
            // zoomToBoundingBox needs a laid-out view; post it.
            view.post { fitTimelineBounds(animated = false) }
        }
    }

    override fun detach() {
        val view = mapView
        view?.removeMapListener(zoomListener)
        view?.setOnTouchListener(null)
        mapView = null
    }

    override fun onResumeView() {
        mapView?.onResume()
    }

    override fun onPauseView() {
        mapView?.onPause()
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------

    override fun setMapStyle(style: BasemapStyle) {
        if (style == this.style && mapView != null) return
        this.style = style
        val view = mapView ?: return
        val spec = BasemapStyles.specFor(style)
        view.setTileSource(patternSource(spec.base))
        applyOverlayTiles()
        view.invalidate()
    }

    override fun setRoute(points: List<MapPoint>) {
        // Reference-diffed: the Compose layer memoizes the list, so repeated
        // updates with the same data are a no-op (no camera yanking).
        if (points === this.points) return
        val hadData = this.points.any { it.isMappable }
        this.points = points
        selectedIndex = null
        val view = mapView
        // A new route invalidates animation overlays (rebuilt below).
        animatedMarker?.let { view?.overlays?.remove(it) }
        animatedMarker = null
        traveledLine?.let { view?.overlays?.remove(it) }
        traveledLine = null
        traveledGeo.clear()
        lastTraveledCount = -1
        pendingProgress = null
        highlightMarker?.let { view?.overlays?.remove(it) }
        highlightMarker = null
        if (view == null) {
            // Data arrived before the view exists: frame it on attach.
            if (points.any { it.isMappable }) pendingFit = true
            return
        }
        rebuildRoute()
        rebuildPointMarkers()
        if (points.any { it.isMappable } && !hadData) {
            // A newly loaded journey: frame it once the view is laid out.
            view.post { fitTimelineBounds(animated = false) }
        }
    }

    override fun clearRoute() {
        setRoute(emptyList())
    }

    override fun setSelectedPoint(index: Int?) {
        if (index == selectedIndex && mapView != null) return
        selectedIndex = index
        if (mapView == null) return
        applySelection()
    }

    override fun moveCameraToPoint(index: Int, zoom: Double?) {
        val view = mapView
        val p = points.find { it.index == index }?.takeIf { it.isMappable }
        if (view == null || p == null) return
        if (zoom != null) view.controller.setZoom(zoom.coerceIn(2.0, 19.0))
        view.controller.animateTo(GeoPoint(p.lat, p.lng))
    }

    override fun fitTimelineBounds(animated: Boolean) {
        val view = mapView
        if (view == null) {
            pendingFit = true
            return
        }
        val bounds = MapDataMapper.boundsOf(points) ?: return
        if (bounds.minLat == bounds.maxLat && bounds.minLng == bounds.maxLng) {
            view.controller.setCenter(GeoPoint(bounds.centerLat, bounds.centerLng))
            view.controller.setZoom(SINGLE_POINT_ZOOM)
        } else {
            val box = BoundingBox(
                bounds.maxLat, bounds.maxLng,
                bounds.minLat, bounds.minLng,
            )
            view.zoomToBoundingBox(box, animated, fitBorderPx())
        }
    }

    override fun getCurrentCameraState(): MapCameraState {
        val view = mapView ?: return MapCameraState(0.0, 0.0, DEFAULT_ZOOM)
        val center = view.mapCenter
        return MapCameraState(center.latitude, center.longitude, view.zoomLevelDouble)
    }

    // ------------------------------------------------------------------
    // Animation surface (Phase 5)
    // ------------------------------------------------------------------

    override fun setRouteDrawMode(mode: RouteDrawMode) {
        routeDrawMode = mode
        if (mapView == null) return
        applyRouteDrawMode()
    }

    override fun setAnimatedMarkerPosition(lat: Double, lng: Double) {
        if (animatedMarkerStyle == VideoMarkerStyle.HIDDEN) {
            hideAnimatedMarker()
            return
        }
        val view = mapView ?: return
        var marker = animatedMarker
        if (marker == null) {
            marker = Marker(view).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = animatedDotIcon(animatedMarkerStyle)
                // Consume taps; static event dots stay tappable underneath.
                setOnMarkerClickListener { _, _ -> true }
            }
            view.overlays.add(marker)
            animatedMarker = marker
            animatedMarkerIconStyle = animatedMarkerStyle
        }
        if (animatedMarkerIconStyle != animatedMarkerStyle) {
            marker.icon = animatedDotIcon(animatedMarkerStyle)
            animatedMarkerIconStyle = animatedMarkerStyle
        }
        marker.position = GeoPoint(lat, lng)
        view.invalidate()
    }

    override fun hideAnimatedMarker() {
        val view = mapView
        animatedMarker?.let { view?.overlays?.remove(it) }
        animatedMarker = null
        view?.invalidate()
    }

    override fun setAnimatedRouteProgress(traveledPointCount: Int, lat: Double, lng: Double) {
        val view = mapView
        val line = traveledLine
        if (view == null || line == null) {
            pendingProgress = Triple(traveledPointCount, lat, lng)
            return
        }
        if (routeDrawMode != RouteDrawMode.PROGRESSIVE || !routeVisible) return
        val mappable = points.mappable()
        val count = traveledPointCount.coerceIn(0, mappable.size)
        if (count != lastTraveledCount) {
            // Segment boundary crossed (or a seek): rebuild the prefix once.
            traveledGeo.clear()
            for (i in 0 until count) {
                traveledGeo.add(GeoPoint(mappable[i].lat, mappable[i].lng))
            }
            traveledGeo.add(GeoPoint(lat, lng)) // cursor placeholder
            lastTraveledCount = count
        } else if (traveledGeo.isNotEmpty()) {
            // Same segment: just move the cursor — no reallocation.
            traveledGeo[traveledGeo.size - 1] = GeoPoint(lat, lng)
        } else {
            traveledGeo.add(GeoPoint(lat, lng))
        }
        // Mutate the polyline's live list in place: no per-frame allocation.
        // (Polyline.getPoints() returns a deprecated copy — getActualPoints()
        // is the live list.)
        val pts = line.actualPoints
        pts.clear()
        pts.addAll(traveledGeo)
        view.invalidate()
    }

    override fun followAnimatedMarker(lat: Double, lng: Double) {
        val view = mapView ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastFollowMs < followThrottleMs) return
        // Recenter only when the marker leaves the central viewport area, so
        // the camera glides instead of jumping every frame.
        val zoom = view.zoomLevelDouble
        val metersPerPx = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
        val thresholdM = metersPerPx * view.height * followViewportFraction
        val center = view.mapCenter
        val distM = GeoPoint(center.latitude, center.longitude)
            .distanceToAsDouble(GeoPoint(lat, lng))
        if (distM > thresholdM) {
            lastFollowMs = now
            view.controller.animateTo(GeoPoint(lat, lng))
        }
    }

    override fun setOnUserInteractionListener(listener: (() -> Unit)?) {
        userInteractionListener = listener
    }

    override fun recenterOn(lat: Double, lng: Double) {
        val view = mapView ?: return
        lastFollowMs = SystemClock.uptimeMillis()
        view.controller.animateTo(GeoPoint(lat, lng))
    }

    // ------------------------------------------------------------------
    // Video composition surface (Phase 6)
    // ------------------------------------------------------------------

    override fun setFollowTuning(viewportFraction: Float, throttleMs: Long) {
        followViewportFraction = viewportFraction.coerceIn(0.1f, 0.9f)
        followThrottleMs = throttleMs.coerceIn(100L, 2000L)
    }

    override fun setAnimatedMarkerStyle(style: VideoMarkerStyle) {
        if (style == animatedMarkerStyle) return
        animatedMarkerStyle = style
        if (style == VideoMarkerStyle.HIDDEN) {
            hideAnimatedMarker()
            return
        }
        animatedMarker?.let { marker ->
            marker.icon = animatedDotIcon(style)
            animatedMarkerIconStyle = style
            mapView?.invalidate()
        }
    }

    override fun setRouteAppearance(visible: Boolean, widthScale: Float, opacity: Float) {
        routeVisible = visible
        routeWidthScale = widthScale.coerceIn(0.5f, 2f)
        routeOpacity = opacity.coerceIn(0.2f, 1f)
        if (mapView == null) return
        applyRouteDrawMode()
    }

    override fun setStartEndMarkersVisible(visible: Boolean) {
        if (visible == startEndMarkersVisible) return
        startEndMarkersVisible = visible
        if (mapView != null) rebuildRoute()
    }

    override fun setOnMarkerClickListener(listener: ((index: Int) -> Unit)?) {
        markerListener = listener
    }

    // ------------------------------------------------------------------
    // Tiles
    // ------------------------------------------------------------------

    private fun patternSource(spec: TileLayerSpec): OnlineTileSourceBase =
        object : OnlineTileSourceBase(
            "jv-${spec.urlPattern.hashCode()}",
            spec.minZoom, spec.maxZoom, 256, "",
            arrayOf(""), spec.attribution,
        ) {
            override fun getTileURLString(pTile: Long): String =
                spec.tileUrl(
                    MapTileIndex.getZoom(pTile),
                    MapTileIndex.getX(pTile),
                    MapTileIndex.getY(pTile),
                )
        }

    /** Hybrid's transparent label layer sits right above the base tiles. */
    private fun applyOverlayTiles() {
        val view = mapView ?: return
        labelOverlay?.let { view.overlays.remove(it) }
        labelOverlay = null
        val overlaySpec = BasemapStyles.specFor(style).overlay ?: run {
            view.invalidate()
            return
        }
        val provider = MapTileProviderBasic(appContext, patternSource(overlaySpec))
        val overlay = TilesOverlay(provider, appContext)
        val overlays = view.overlays
        if (overlays.size >= 1) overlays.add(1, overlay) else overlays.add(overlay)
        labelOverlay = overlay
        view.invalidate()
    }

    // ------------------------------------------------------------------
    // Route + markers
    // ------------------------------------------------------------------

    private fun rebuildRoute() {
        val view = mapView ?: return
        routeLine?.let { view.overlays.remove(it) }
        routeLine = null
        traveledLine?.let { view.overlays.remove(it) }
        traveledLine = null
        startMarker?.let { view.overlays.remove(it) }
        endMarker?.let { view.overlays.remove(it) }
        startMarker = null
        endMarker = null

        val mappable = points.mappable()
        if (mappable.size >= 2) {
            val line = Polyline()
            // Chronological order, display-thinned for very long journeys.
            val drawn = MapDataMapper.decimateChronological(mappable, MAX_ROUTE_POINTS)
            // NOTE: Polyline.getPoints() returns a deprecated *copy* in 6.1.20;
            // setPoints() writes to the live list.
            line.setPoints(drawn.map { GeoPoint(it.lat, it.lng) })
            line.setColor(ROUTE_COLOR)
            line.setWidth(routeWidthPx())
            line.setOnClickListener { _, _, _ -> false }
            view.overlays.add(line)
            routeLine = line

            // Traveled-portion overlay for PROGRESSIVE mode; hidden in FULL.
            val traveled = Polyline()
            traveled.setColor(TRAVELED_COLOR)
            traveled.setWidth(routeWidthPx() * 1.25f)
            traveled.setOnClickListener { _, _, _ -> false }
            traveledLine = traveled

            startMarker = null
            endMarker = null
            if (startEndMarkersVisible) {
                startMarker = badge(view, mappable.first(), "S", START_COLOR).also {
                    view.overlays.add(it)
                }
                endMarker = badge(view, mappable.last(), "E", END_COLOR).also {
                    view.overlays.add(it)
                }
            }
        }
        applyRouteDrawMode()
        view.invalidate()
    }

    /**
     * FULL: bright whole route. PROGRESSIVE: dimmed whole route + the
     * traveled overlay on top (grown frame-by-frame by the engine).
     * Phase 6: also applies route visibility/width/opacity from video
     * composition.
     */
    private fun applyRouteDrawMode() {
        val view = mapView ?: return
        val alpha = (routeOpacity.coerceIn(0.2f, 1f) * 255).toInt()
        val widthScale = routeWidthScale.coerceIn(0.5f, 2f)
        routeLine?.let { line ->
            line.setColor(
                withAlpha(
                    if (routeDrawMode == RouteDrawMode.FULL) ROUTE_COLOR else ROUTE_COLOR_DIM,
                    alpha,
                ),
            )
            line.setWidth(routeWidthPx() * widthScale)
            if (routeVisible) {
                if (!view.overlays.contains(line)) view.overlays.add(line)
            } else {
                view.overlays.remove(line)
            }
        }
        val traveled = traveledLine
        if (routeDrawMode == RouteDrawMode.PROGRESSIVE && routeVisible) {
            if (traveled != null) {
                traveled.setColor(withAlpha(TRAVELED_COLOR, alpha))
                traveled.setWidth(routeWidthPx() * 1.25f * widthScale)
                if (!view.overlays.contains(traveled)) {
                    view.overlays.add(traveled)
                }
            }
        } else {
            if (traveled != null) view.overlays.remove(traveled)
        }
        view.invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /**
     * Grid clustering at the current zoom: points sharing a cell become one
     * count badge; lone points become tappable dots. Rebuilt on zoom change
     * only — panning needs no rebuild.
     */
    private fun rebuildPointMarkers() {
        val view = mapView ?: return
        for (m in pointMarkers) view.overlays.remove(m)
        pointMarkers.clear()
        markerIndex.clear()

        var mappable = points.mappable()
        if (mappable.isEmpty()) {
            view.invalidate()
            return
        }
        // Safety valve: thin the *marker set* (never the route/records) so
        // even a 50k-point timeline stays interactive.
        if (mappable.size > MAX_CLUSTER_INPUT) {
            val stride = mappable.size.toDouble() / MAX_CLUSTER_INPUT
            mappable = List(MAX_CLUSTER_INPUT) { mappable[(it * stride).toInt()] }
        }

        val zoom = view.zoomLevelDouble
        val cell = 360.0 / 2.0.pow(zoom) / CELLS_PER_TILE
        val grid = LinkedHashMap<Pair<Int, Int>, MutableList<MapPoint>>()
        for (p in mappable) {
            val key = floor(p.lng / cell).toInt() to floor(p.lat / cell).toInt()
            grid.getOrPut(key) { ArrayList() }.add(p)
        }

        for (cellPoints in grid.values) {
            val marker = Marker(view)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            if (cellPoints.size == 1) {
                val p = cellPoints[0]
                marker.position = GeoPoint(p.lat, p.lng)
                marker.icon = dotIcon()
                markerIndex[marker] = p.index
                marker.setOnMarkerClickListener { _, _ ->
                    markerListener?.invoke(p.index)
                    true
                }
            } else {
                val lat = cellPoints.sumOf { it.lat } / cellPoints.size
                val lng = cellPoints.sumOf { it.lng } / cellPoints.size
                marker.position = GeoPoint(lat, lng)
                marker.icon = clusterIcon(cellPoints.size)
                marker.setOnMarkerClickListener { m, v ->
                    v.controller.animateTo(m.position)
                    v.controller.setZoom((v.zoomLevelDouble + 1).coerceAtMost(19.0))
                    true
                }
            }
            view.overlays.add(marker)
            pointMarkers.add(marker)
        }
        view.invalidate()
    }

    private fun applySelection() {
        val view = mapView ?: return
        highlightMarker?.let { view.overlays.remove(it) }
        highlightMarker = null
        val index = selectedIndex ?: run {
            view.invalidate()
            return
        }
        val p = points.find { it.index == index }?.takeIf { it.isMappable } ?: return
        val marker = Marker(view)
        marker.position = GeoPoint(p.lat, p.lng)
        marker.icon = ringIcon()
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.setOnMarkerClickListener { _, _ -> true }
        view.overlays.add(marker)
        highlightMarker = marker
        view.controller.animateTo(GeoPoint(p.lat, p.lng))
        view.invalidate()
    }

    private fun badge(view: MapView, p: MapPoint, letter: String, color: Int): Marker =
        Marker(view).apply {
            position = GeoPoint(p.lat, p.lng)
            icon = letterIcon(letter, color)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                markerListener?.invoke(p.index)
                true
            }
        }

    // ------------------------------------------------------------------
    // Programmatic icons (no drawable resources needed)
    // ------------------------------------------------------------------

    private val density: Float get() = appContext.resources.displayMetrics.density

    private fun routeWidthPx(): Float = 5f * density

    private fun fitBorderPx(): Int = (56 * density).toInt()

    private fun dotIcon(): Drawable {
        val r = 9f * density
        return circleBitmap(r, POINT_COLOR, null)
    }

    /** The animated position marker: blue, unmistakable vs green dots. */
    private fun animatedDotIcon(style: VideoMarkerStyle = VideoMarkerStyle.STANDARD): Drawable {
        val r = when (style) {
            VideoMarkerStyle.STANDARD -> 12f
            VideoMarkerStyle.MINIMAL_DOT -> 7f
            VideoMarkerStyle.HIGHLIGHTED -> 14f
            VideoMarkerStyle.HIDDEN -> 12f
        } * density
        val dot = circleBitmap(r, ANIMATED_COLOR, null)
        if (style != VideoMarkerStyle.HIGHLIGHTED) return dot
        // Highlight ring around the dot.
        val ringStroke = 3f * density
        val size = ((r + ringStroke) * 2 + 4 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val dotBmp = (dot as BitmapDrawable).bitmap
        canvas.drawBitmap(
            dotBmp,
            (size - dotBmp.width) / 2f,
            (size - dotBmp.height) / 2f,
            null,
        )
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = ringStroke
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, r + ringStroke, ring)
        return BitmapDrawable(appContext.resources, bmp)
    }

    private fun clusterIcon(count: Int): Drawable {
        val r = 16f * density
        val label = if (count > 999) "999+" else count.toString()
        return circleBitmap(r, CLUSTER_COLOR, label)
    }

    private fun letterIcon(letter: String, color: Int): Drawable {
        val r = 15f * density
        return circleBitmap(r, color, letter)
    }

    private fun ringIcon(): Drawable {
        val r = 16f * density
        val stroke = 4f * density
        val size = ((r + stroke) * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            this.color = HIGHLIGHT_COLOR
        }
        canvas.drawCircle(size / 2f, size / 2f, r, paint)
        return BitmapDrawable(appContext.resources, bmp)
    }

    private fun circleBitmap(radiusPx: Float, fill: Int, label: String?): Drawable {
        val size = (radiusPx * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawCircle(size / 2f, size / 2f, radiusPx, paint)
        // Subtle white rim so dots read on satellite imagery too.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, radiusPx - density, rim)
        if (label != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = radiusPx * (if (label.length > 3) 0.62f else 0.85f)
                textAlign = Paint.Align.CENTER
            }
            val y = size / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, size / 2f, y, text)
        }
        return BitmapDrawable(appContext.resources, bmp)
    }

    companion object {
        private const val DEFAULT_ZOOM = 2.0
        private const val SINGLE_POINT_ZOOM = 15.0
        private const val MAX_ROUTE_POINTS = 4000
        private const val MAX_CLUSTER_INPUT = 3000
        private const val CELLS_PER_TILE = 3.0
        /** Min gap between follow recenters — the camera glides, not jumps. */
        private const val FOLLOW_THROTTLE_MS = 400L
        /** Recenter when the marker leaves this fraction of the viewport height. */
        private const val FOLLOW_VIEWPORT_FRACTION = 0.25

        private const val ROUTE_COLOR = 0xFF16A34A // brand green
        private const val ROUTE_COLOR_DIM = 0x6616A34A // faint green (progressive mode)
        private const val TRAVELED_COLOR = 0xFF15803D // emphasized traveled portion
        private const val ANIMATED_COLOR = 0xFF2563EB // blue "you are here" marker
        private const val POINT_COLOR = 0xFF16A34A
        private const val CLUSTER_COLOR = 0xFF15803D
        private const val START_COLOR = 0xFF16A34A
        private const val END_COLOR = 0xFFDC2626
        private const val HIGHLIGHT_COLOR = -0x1 // 0xFFFFFFFF as Int
    }
}
