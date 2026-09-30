package com.journeyvisualizer.app.export

import com.journeyvisualizer.app.animation.AnimationMath
import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.data.model.Bounds
import com.journeyvisualizer.app.map.TileKey
import com.journeyvisualizer.app.map.TileMath
import kotlin.math.cos
import kotlin.math.pow

/** Camera state for one export frame: center + fractional zoom. */
data class ExportCamera(val lat: Double, val lng: Double, val zoom: Double)

/**
 * Deterministic export camera (Phase 7). Pure Kotlin — no Android dependency.
 *
 * Mirrors the Phase 6 preview's camera behavior as closely as a
 * deterministic renderer can:
 *
 * - [CompositionCameraMode.FIXED_OVERVIEW]: the whole route fitted in the
 *   frame, exactly like the preview's `fitTimelineBounds`.
 * - [CompositionCameraMode.FOLLOW_JOURNEY] / [CompositionCameraMode.SMART_FOLLOW]:
 *   the preview's "recenter when the marker leaves the central viewport"
 *   follow ([OsmMapController.followAnimatedMarker]), but driven by *video
 *   time* instead of wall-clock. The tuning matches the preview exactly:
 *   FOLLOW_JOURNEY = 0.25 viewport fraction / 0.4 s throttle,
 *   SMART_FOLLOW = 0.40 / 0.8 s (see VideoPreviewScreen).
 *
 * The planner is stateful across frames in follow modes and MUST be driven
 * in frame order starting at frame 0. That ordered walk is itself a pure
 * function of the frame sequence, so every replay produces the identical
 * camera path — the same frame timestamp always yields the same camera.
 */
class ExportCameraPlanner(
    points: List<CamPoint>,
    private val mode: CompositionCameraMode,
    private val widthPx: Int,
    private val heightPx: Int,
    /** Provider's max zoom (OpenTopoMap caps at 17); the camera never exceeds it. */
    private val maxZoom: Int = TileMath.MAX_ZOOM,
) {
    data class CamPoint(val lat: Double, val lng: Double)

    /** Viewport fraction that triggers a follow recenter (matches preview). */
    val viewportFraction: Float = when (mode) {
        CompositionCameraMode.SMART_FOLLOW -> 0.40f
        else -> 0.25f
    }

    /** Minimum video-time between follow recenters, seconds (matches preview). */
    val throttleSec: Double = when (mode) {
        CompositionCameraMode.SMART_FOLLOW -> 0.8
        else -> 0.4
    }

    /** Fitted whole-route camera; also the follow start state (like the preview). */
    val overviewCamera: ExportCamera = computeOverview(points)

    private var centerLat: Double = overviewCamera.lat
    private var centerLng: Double = overviewCamera.lng
    private var lastRecenterSec: Double = -1e9

    /**
     * Camera for the frame at [videoTimeSec] showing the marker at
     * ([markerLat], [markerLng]). Call in ascending frame order from 0.
     */
    fun cameraFor(videoTimeSec: Double, markerLat: Double, markerLng: Double): ExportCamera {
        if (mode == CompositionCameraMode.FIXED_OVERVIEW) return overviewCamera
        val zoom = overviewCamera.zoom
        val metersPerPx = METERS_PER_PX_AT_Z0 * cos(Math.toRadians(markerLat)) / 2.0.pow(zoom)
        val thresholdM = metersPerPx * heightPx * viewportFraction
        val distM = AnimationMath.distanceM(centerLat, centerLng, markerLat, markerLng)
        if (distM > thresholdM && videoTimeSec - lastRecenterSec >= throttleSec) {
            centerLat = markerLat
            centerLng = markerLng
            lastRecenterSec = videoTimeSec
        }
        return ExportCamera(centerLat, centerLng, zoom)
    }

    /**
     * Tile keys visible at [camera]. Used identically by the tile prefetcher
     * and the frame renderer, so prefetch coverage and rendering agree.
     */
    fun visibleTileKeys(camera: ExportCamera, minZoom: Int, maxZoom: Int): Set<TileKey> {
        val z0 = camera.zoom.toInt().coerceIn(minZoom, maxZoom)
        val scale = 2.0.pow(camera.zoom - z0)
        val tilePx = TileMath.TILE_SIZE * scale
        val cx = TileMath.lngToWorldX(camera.lng, camera.zoom)
        val cy = TileMath.latToWorldY(camera.lat, camera.zoom)
        val n = 1 shl z0
        val keys = LinkedHashSet<TileKey>()
        val left = ((cx - widthPx / 2.0) / tilePx).toInt()
        val right = ((cx + widthPx / 2.0) / tilePx).toInt()
        val top = ((cy - heightPx / 2.0) / tilePx).toInt()
        val bottom = ((cy + heightPx / 2.0) / tilePx).toInt()
        for (x in left..right) {
            for (y in top..bottom) {
                if (y < 0 || y >= n) continue
                keys.add(TileKey(z0, ((x % n) + n) % n, y))
            }
        }
        return keys
    }

    private fun computeOverview(points: List<CamPoint>): ExportCamera {
        require(points.isNotEmpty()) { "need at least one point" }
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val centerLat = (minLat + maxLat) / 2
        val centerLng = (minLng + maxLng) / 2
        if (minLat == maxLat && minLng == maxLng) {
            // Matches the preview's SINGLE_POINT_ZOOM.
            return ExportCamera(centerLat, centerLng, SINGLE_POINT_ZOOM)
        }
        val bounds = Bounds(minLat, minLng, maxLat, maxLng)
        val paddingPx = (0.08 * minOf(widthPx, heightPx)).toInt()
        val zoom = TileMath.fitZoom(bounds, widthPx, heightPx, paddingPx)
            .toDouble()
            .coerceIn(TileMath.MIN_ZOOM.toDouble(), maxZoom.toDouble())
        return ExportCamera(centerLat, centerLng, zoom)
    }

    companion object {
        private const val SINGLE_POINT_ZOOM = 15.0
        private const val METERS_PER_PX_AT_Z0 = 156543.03392
    }
}
