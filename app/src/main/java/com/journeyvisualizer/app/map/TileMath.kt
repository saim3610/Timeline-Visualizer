package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.data.model.Bounds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.atan

data class TileKey(val z: Int, val x: Int, val y: Int)

/** Web Mercator math shared by the preview and the video exporter. */
object TileMath {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 19

    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    fun lngToWorldX(lng: Double, zoom: Double): Double =
        (lng + 180.0) / 360.0 * worldSize(zoom)

    fun latToWorldY(lat: Double, zoom: Double): Double {
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val s = sin(Math.toRadians(clamped))
        val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        return y * worldSize(zoom)
    }

    fun worldYToLat(y: Double, zoom: Double): Double {
        val n = PI * (1 - 2 * y / worldSize(zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Highest integer zoom at which [bounds] fits inside the viewport (with padding). */
    fun fitZoom(bounds: Bounds, widthPx: Int, heightPx: Int, paddingPx: Int): Int {
        val w = (widthPx - 2 * paddingPx).coerceAtLeast(1).toDouble()
        val h = (heightPx - 2 * paddingPx).coerceAtLeast(1).toDouble()
        var lo = MIN_ZOOM.toDouble()
        var hi = MAX_ZOOM.toDouble()
        repeat(24) {
            val mid = (lo + hi) / 2
            val bw = abs(lngToWorldX(bounds.maxLng, mid) - lngToWorldX(bounds.minLng, mid))
            val bh = abs(latToWorldY(bounds.minLat, mid) - latToWorldY(bounds.maxLat, mid))
            if (bw <= w && bh <= h) lo = mid else hi = mid
        }
        return lo.toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
