package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.data.model.Bounds
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.data.model.haversineM
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

enum class CameraMode { STEADY, DYNAMIC, FIXED }

/**
 * How video time maps onto journey time.
 * - LINEAR: one video second per journey second (uniform).
 * - COMPRESS_STOPS: stopped time (traffic, meals, overnight stays) is
 *   squeezed so long trips stay watchable; moving time keeps full weight.
 */
enum class TimeWarp { LINEAR, COMPRESS_STOPS }

data class CameraState(val lat: Double, val lng: Double, val zoom: Double)

/**
 * Pure journey logic shared by the interactive preview and the MP4 exporter,
 * so both render exactly the same animation. Everything here is deterministic
 * in video time, which is what makes preview == export.
 */
class JourneyEngine(val journey: Journey, val timeWarp: TimeWarp = TimeWarp.LINEAR) {

    companion object {
        /** Final seconds of the video: zoom out to reveal the whole route. */
        const val ENDING_SEC = 1.5
        /** Below this speed a segment counts as "stopped" for COMPRESS_STOPS. */
        private const val STOP_SPEED_MPS = 0.8
        /** Stopped time gets this fraction of video time compared to moving time. */
        private const val STOP_WEIGHT = 0.12
        /** One segment can claim at most this much weight (10 minutes). */
        private const val MAX_SEG_WEIGHT_SEC = 600.0
    }

    private val pts = journey.points

    private val crossesDateline: Boolean = run {
        val b = journey.bounds ?: return@run false
        (b.maxLng - b.minLng) > 180.0
    }

    /** Longitude in render space (unwrapped past the date line when needed). */
    private fun rl(lng: Double): Double =
        if (crossesDateline && lng < 0) lng + 360.0 else lng

    val renderBounds: Bounds? by lazy {
        val b = journey.bounds ?: return@lazy null
        if (!crossesDateline) {
            b
        } else {
            var mn = Double.MAX_VALUE
            var mx = -Double.MAX_VALUE
            for (p in pts) {
                val l = rl(p.lng)
                if (l < mn) mn = l
                if (l > mx) mx = l
            }
            Bounds(b.minLat, b.maxLat, mn, mx)
        }
    }

    /**
     * Cumulative video-time weight; cumWeight[i] covers the segment ending at
     * pts[i]. Stopped segments weigh less under COMPRESS_STOPS.
     */
    private val cumWeight = DoubleArray(pts.size)
    private val totalWeight: Double = run {
        var acc = 0.0
        for (i in pts.indices) {
            val dtSec = if (i == 0) {
                0.0
            } else {
                ((pts[i].timeMs - pts[i - 1].timeMs).coerceAtLeast(0L) / 1000.0)
                    .coerceAtMost(MAX_SEG_WEIGHT_SEC)
            }
            var w = 1.0
            if (timeWarp == TimeWarp.COMPRESS_STOPS && i > 0 && dtSec > 0) {
                val d = haversineM(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
                if (d / dtSec < STOP_SPEED_MPS) w = STOP_WEIGHT
            }
            acc += w * dtSec
            cumWeight[i] = acc
        }
        acc
    }

    fun filterRange(startMs: Long, endMs: Long): Journey =
        Journey(journey.title, journey.sourceName, pts.filter { it.timeMs in startMs..endMs })

    /** Segment index whose end timestamp >= timeMs (0 when before the start). */
    private fun segmentAt(timeMs: Long): Int {
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (pts[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Interpolated (lat, renderLng) at a journey timestamp. */
    fun positionAt(timeMs: Long): Pair<Double, Double> {
        if (pts.isEmpty()) return 0.0 to 0.0
        val first = pts.first()
        val last = pts.last()
        if (timeMs <= first.timeMs) return first.lat to rl(first.lng)
        if (timeMs >= last.timeMs) return last.lat to rl(last.lng)
        val hi = segmentAt(timeMs)
        val a = pts[hi - 1]
        val b = pts[hi]
        val frac = (timeMs - a.timeMs).toDouble() /
            (b.timeMs - a.timeMs).coerceAtLeast(1L).toDouble()
        val aLng = rl(a.lng)
        return (a.lat + frac * (b.lat - a.lat)) to (aLng + frac * (rl(b.lng) - aLng))
    }

    /** Speed in m/s at a journey timestamp. */
    fun speedAt(timeMs: Long): Double {
        if (pts.size < 2) return 0.0
        val hi = segmentAt(timeMs).coerceAtLeast(1)
        val a = pts[hi - 1]
        val b = pts[hi]
        val dt = (b.timeMs - a.timeMs).coerceAtLeast(1L) / 1000.0
        return haversineM(a.lat, a.lng, b.lat, b.lng) / dt
    }

    /** Video fraction (0..1 of the animated part) -> journey timestamp. */
    fun videoToJourneyMs(fraction: Double): Long {
        if (pts.isEmpty()) return 0L
        val f = fraction.coerceIn(0.0, 1.0)
        if (timeWarp == TimeWarp.LINEAR || totalWeight <= 0.0) {
            val span = (journey.endMs - journey.startMs).coerceAtLeast(1L)
            return journey.startMs + (span * f).toLong()
        }
        val target = f * totalWeight
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumWeight[mid] < target) lo = mid + 1 else hi = mid
        }
        val i = lo
        if (i == 0) return pts[0].timeMs
        val prev = cumWeight[i - 1]
        val seg = cumWeight[i] - prev
        if (seg <= 1e-9) return pts[i].timeMs
        val sf = ((target - prev) / seg).coerceIn(0.0, 1.0)
        return (pts[i - 1].timeMs + sf * (pts[i].timeMs - pts[i - 1].timeMs)).toLong()
    }

    /** Maps a video timestamp (0..durationSec) to a journey timestamp. */
    fun journeyTimeAt(videoTimeSec: Double, durationSec: Double): Long {
        val anim = (durationSec - ENDING_SEC).coerceAtLeast(1.0)
        return videoToJourneyMs(videoTimeSec.coerceIn(0.0, anim) / anim)
    }

    fun cameraAt(
        videoTimeSec: Double,
        durationSec: Double,
        mode: CameraMode,
        viewW: Int,
        viewH: Int,
    ): CameraState {
        val bounds = renderBounds ?: return CameraState(0.0, 0.0, 2.0)
        val padding = (min(viewW, viewH) * 0.12).toInt()
        val fitZoom = TileMath.fitZoom(bounds, viewW, viewH, padding).toDouble()
        val anim = (durationSec - ENDING_SEC).coerceAtLeast(1.0)

        if (videoTimeSec >= anim) {
            val k = ((videoTimeSec - anim) / ENDING_SEC).coerceIn(0.0, 1.0)
            val eased = 1 - (1 - k) * (1 - k)
            return CameraState(bounds.centerLat, bounds.centerLng, fitZoom - 1.5 * eased)
        }

        val jt = journeyTimeAt(videoTimeSec, durationSec)
        return when (mode) {
            CameraMode.FIXED ->
                CameraState(bounds.centerLat, bounds.centerLng, fitZoom)
            CameraMode.STEADY -> {
                // Smooth follow: weighted average of the trailing ~4 seconds of travel.
                var wSum = 0.0
                var la = 0.0
                var ln = 0.0
                val samples = 9
                for (i in 0 until samples) {
                    val w = (samples - i).toDouble()
                    val (plat, plng) = positionAt(jt - i * 500L)
                    la += plat * w
                    ln += plng * w
                    wSum += w
                }
                CameraState(la / wSum, ln / wSum, fitZoom)
            }
            CameraMode.DYNAMIC -> {
                // Follows with a 3-second lookahead; zooms out as speed rises.
                val ahead = journeyTimeAt(
                    (videoTimeSec + 3.0).coerceAtMost(anim), durationSec
                )
                val (la1, ln1) = positionAt(jt)
                val (la2, ln2) = positionAt(ahead)
                val zoomOut = (speedAt(jt) / 15.0).coerceIn(0.0, 2.5)
                CameraState(
                    (la1 + la2) / 2.0,
                    (ln1 + ln2) / 2.0,
                    (fitZoom - zoomOut).coerceIn(3.0, 19.0),
                )
            }
        }
    }

    /** Projects a lat/lng to screen pixels for the given camera. */
    fun toScreen(
        lat: Double, lng: Double, cam: CameraState, viewW: Int, viewH: Int,
    ): Pair<Float, Float> {
        val z = cam.zoom
        val cx = TileMath.lngToWorldX(cam.lng, z)
        val cy = TileMath.latToWorldY(cam.lat, z)
        val x = TileMath.lngToWorldX(rl(lng), z) - cx + viewW / 2.0
        val y = TileMath.latToWorldY(lat, z) - cy + viewH / 2.0
        return x.toFloat() to y.toFloat()
    }

    /** Integer-zoom tile keys covering the viewport for the camera. */
    fun visibleTiles(cam: CameraState, viewW: Int, viewH: Int): List<TileKey> {
        val z = cam.zoom.toInt().coerceIn(TileMath.MIN_ZOOM, TileMath.MAX_ZOOM)
        val scale = 2.0.pow(cam.zoom - z)
        val cx = TileMath.lngToWorldX(cam.lng, cam.zoom)
        val cy = TileMath.latToWorldY(cam.lat, cam.zoom)
        val left = (cx - viewW / 2.0) / scale
        val top = (cy - viewH / 2.0) / scale
        val right = (cx + viewW / 2.0) / scale
        val bottom = (cy + viewH / 2.0) / scale
        val out = ArrayList<TileKey>()
        for (tx in floor(left / TileMath.TILE_SIZE).toInt()..floor(right / TileMath.TILE_SIZE).toInt()) {
            for (ty in floor(top / TileMath.TILE_SIZE).toInt()..floor(bottom / TileMath.TILE_SIZE).toInt()) {
                out.add(TileKey(z, tx, ty))
            }
        }
        return out
    }

    /**
     * Tile keys covering the whole journey at zooms around the fit zoom,
     * for export prefetching / offline caching. Capped so huge trips stay sane.
     */
    fun warmTileKeys(viewW: Int, viewH: Int): List<TileKey> {
        val bounds = renderBounds ?: return emptyList()
        val padding = (min(viewW, viewH) * 0.12).toInt()
        val base = TileMath.fitZoom(bounds, viewW, viewH, padding)
        val keys = LinkedHashSet<TileKey>()
        for (dz in -1..1) {
            val z = (base + dz).coerceIn(TileMath.MIN_ZOOM, TileMath.MAX_ZOOM)
            val x0 = floor(TileMath.lngToWorldX(bounds.minLng, z.toDouble()) / TileMath.TILE_SIZE).toInt()
            val x1 = floor(TileMath.lngToWorldX(bounds.maxLng, z.toDouble()) / TileMath.TILE_SIZE).toInt()
            val y0 = floor(TileMath.latToWorldY(bounds.maxLat, z.toDouble()) / TileMath.TILE_SIZE).toInt()
            val y1 = floor(TileMath.latToWorldY(bounds.minLat, z.toDouble()) / TileMath.TILE_SIZE).toInt()
            for (tx in x0..x1) {
                for (ty in y0..y1) {
                    keys.add(TileKey(z, tx, ty))
                    if (keys.size >= 600) return keys.toList()
                }
            }
        }
        return keys.toList()
    }
}
