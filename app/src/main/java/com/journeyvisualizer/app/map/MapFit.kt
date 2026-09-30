package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.data.model.Bounds
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/**
 * NaN-safe camera-fit math for the timeline map. Android-free, so it is
 * unit-tested on the JVM (see MapFitTest).
 *
 * Background: osmdroid's MapView.zoomToBoundingBox must never be called from
 * this app. When the view is not larger than the fit borders — zero-size on
 * first layout, or merely *small* — it derives a NaN zoom (log of a
 * non-positive available size); NaN propagates into Projection's mercator map
 * size, and Projection.getCloserPixel then spins forever (x -= NaN never
 * changes x, so its loop condition never flips), freezing the main thread
 * until the OS kills the app. Two on-device ANR reports (2026-10-01)
 * confirmed this exact stack — the second one AFTER a "wait for non-zero
 * size" guard, because a small-but-nonzero view slips through such a guard.
 *
 * This object replicates only the *zoom* half of osmdroid's
 * TileSystem.getBoundingBoxZoom (Web-Mercator, 256px tiles — the osmdroid
 * default), with every degenerate input normalized to a finite value. The
 * caller applies the result via IMapController.setZoom/setCenter (or
 * animateTo), which perform no projection math and cannot hang.
 */
object MapFit {

    /** Successful fit: zoom to apply and center to apply it at. */
    data class Fit(val zoom: Double, val centerLat: Double, val centerLng: Double)

    /**
     * Computes the camera fit for [bounds] inside a
     * [viewWidthPx]×[viewHeightPx] view with [borderPx] padding per side.
     *
     * Returns null when no fit can be computed yet — the view is not larger
     * than the borders, or the bounds are not finite. The caller must wait
     * for layout and retry instead of fitting. A returned zoom is always
     * finite and within [minZoom]..[maxZoom].
     */
    fun fitForBounds(
        bounds: Bounds,
        viewWidthPx: Int,
        viewHeightPx: Int,
        borderPx: Int,
        minZoom: Double,
        maxZoom: Double,
    ): Fit? {
        if (!bounds.isFinite()) return null
        val availW = viewWidthPx - 2 * borderPx
        val availH = viewHeightPx - 2 * borderPx
        if (availW <= 0 || availH <= 0) return null
        val zoom = zoomForBounds(bounds, availW, availH).coerceIn(minZoom, maxZoom)
        return Fit(zoom, bounds.centerLat, bounds.centerLng)
    }

    /**
     * Zoom at which [bounds] exactly fills [availWidthPx]×[availHeightPx].
     * Requires positive available sizes and finite bounds. Always finite:
     * a degenerate span fits toward +infinity ("as close as possible"),
     * which callers clamp to max zoom. Note: unlike osmdroid, which zooms a
     * one-dimensional span all the way to max zoom, this fits the remaining
     * dimension — framing a horizontal/vertical line of points instead of a
     * meaningless close-up.
     */
    fun zoomForBounds(bounds: Bounds, availWidthPx: Int, availHeightPx: Int): Double {
        require(availWidthPx > 0 && availHeightPx > 0) { "available size must be positive" }
        require(bounds.isFinite()) { "bounds must be finite" }
        return minOf(
            longitudeZoom(bounds.minLng, bounds.maxLng, availWidthPx),
            latitudeZoom(bounds.minLat, bounds.maxLat, availHeightPx),
        )
    }

    private fun Bounds.isFinite(): Boolean =
        minLat.isFinite() && maxLat.isFinite() && minLng.isFinite() && maxLng.isFinite()

    // -- Web-Mercator unit-square math (mirrors osmdroid's TileSystemWebMercator) --

    private const val TILE_SIZE_PX = 256
    private const val LN2 = 0.6931471805599453
    private const val MAX_MERCATOR_LAT = 85.05112878

    private fun x01(longitude: Double): Double =
        ((longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0).coerceIn(0.0, 1.0)

    private fun y01(latitude: Double): Double {
        val sinus = sin(latitude.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT) * PI / 180.0)
        return (0.5 - ln((1.0 + sinus) / (1.0 - sinus)) / (4.0 * PI)).coerceIn(0.0, 1.0)
    }

    /** Mirrors TileSystem.getLongitudeZoom; degenerate span -> +infinity. */
    private fun longitudeZoom(west: Double, east: Double, screenWidthPx: Int): Double {
        var span = x01(east) - x01(west)
        if (span < 0.0) span += 1.0 // date-line wrap
        if (span <= 0.0) return Double.POSITIVE_INFINITY
        return ln(screenWidthPx / span / TILE_SIZE_PX) / LN2
    }

    /** Mirrors TileSystem.getLatitudeZoom; degenerate span -> +infinity. */
    private fun latitudeZoom(south: Double, north: Double, screenHeightPx: Int): Double {
        val span = y01(south) - y01(north)
        if (span <= 0.0) return Double.POSITIVE_INFINITY
        return ln(screenHeightPx / span / TILE_SIZE_PX) / LN2
    }
}
