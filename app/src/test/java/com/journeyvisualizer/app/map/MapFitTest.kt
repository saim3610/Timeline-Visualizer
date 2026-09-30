package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.data.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Regression tests for the Timeline Preview main-thread freeze (two on-device
 * ANRs, 2026-10-01): osmdroid's MapView.zoomToBoundingBox derives a NaN zoom
 * when the view is not larger than the fit borders, and
 * Projection.getCloserPixel then loops forever. MapFit.fitForBounds must
 * defer (null) instead of ever producing NaN, for every view size.
 */
class MapFitTest {

    // Bounds(minLat, maxLat, minLng, maxLng)
    private val lahore = Bounds(31.4, 31.6, 74.2, 74.4)
    private val minZoom = 4.0
    private val maxZoom = 19.0
    private val border = 168 // 56dp @ 3x density

    @Test
    fun `lahore bounds on a phone screen give a sane finite zoom`() {
        val fit = MapFit.fitForBounds(lahore, 1080, 2400, border, minZoom, maxZoom)
        assertNotNull("expected a fit", fit)
        fit!!
        assertTrue("zoom finite: ${fit.zoom}", fit.zoom.isFinite())
        assertTrue("zoom in range: ${fit.zoom}", fit.zoom in minZoom..maxZoom)
        // Hand-computed from the Web-Mercator formula: ~12.36.
        assertTrue("zoom near 12.36, was ${fit.zoom}", abs(fit.zoom - 12.36) < 0.6)
        assertEquals(31.5, fit.centerLat, 1e-9)
        assertEquals(74.3, fit.centerLng, 1e-9)
    }

    @Test
    fun `zero-size view defers instead of fitting`() {
        assertNull(MapFit.fitForBounds(lahore, 0, 0, border, minZoom, maxZoom))
        assertNull(MapFit.fitForBounds(lahore, 1080, 0, border, minZoom, maxZoom))
    }

    @Test
    fun `view not larger than borders defers - the second ANR regression`() {
        // 0 < size <= 2*border slipped through the first "non-zero size"
        // guard: osmdroid then computed log(non-positive) = NaN and hung.
        assertNull(MapFit.fitForBounds(lahore, 2 * border, 2400, border, minZoom, maxZoom))
        assertNull(MapFit.fitForBounds(lahore, 300, 2400, border, minZoom, maxZoom))
        assertNull(MapFit.fitForBounds(lahore, 1080, 2 * border, border, minZoom, maxZoom))
        assertNull(MapFit.fitForBounds(lahore, 1, 1, border, minZoom, maxZoom))
        // ...but one pixel more must fit.
        val fit = MapFit.fitForBounds(lahore, 2 * border + 1, 2 * border + 1, border, minZoom, maxZoom)
        assertNotNull("expected a fit just above the threshold", fit)
        assertTrue(fit!!.zoom.isFinite())
    }

    @Test
    fun `degenerate spans frame the remaining dimension, never NaN`() {
        val sameLng = Bounds(31.4, 31.6, 74.3, 74.3)
        val fitLng = MapFit.fitForBounds(sameLng, 1080, 2400, border, minZoom, maxZoom)
        assertNotNull(fitLng)
        assertTrue(fitLng!!.zoom.isFinite() && fitLng.zoom in minZoom..maxZoom)

        val sameLat = Bounds(31.5, 31.5, 74.2, 74.4)
        val fitLat = MapFit.fitForBounds(sameLat, 1080, 2400, border, minZoom, maxZoom)
        assertNotNull(fitLat)
        assertTrue(fitLat!!.zoom.isFinite() && fitLat.zoom in minZoom..maxZoom)
    }

    @Test
    fun `non-finite bounds never produce a fit`() {
        assertNull(
            MapFit.fitForBounds(
                Bounds(Double.NaN, 31.6, 74.2, 74.4),
                1080, 2400, border, minZoom, maxZoom,
            ),
        )
        assertNull(
            MapFit.fitForBounds(
                Bounds(31.4, 31.6, 74.2, Double.POSITIVE_INFINITY),
                1080, 2400, border, minZoom, maxZoom,
            ),
        )
    }

    @Test
    fun `country-wide bounds zoom out further than city bounds`() {
        val pakistan = Bounds(23.5, 37.0, 61.0, 77.5)
        val fitCountry = MapFit.fitForBounds(pakistan, 1080, 2400, border, minZoom, maxZoom)!!
        val fitCity = MapFit.fitForBounds(lahore, 1080, 2400, border, minZoom, maxZoom)!!
        assertTrue(fitCountry.zoom.isFinite())
        assertTrue(
            "country zoom ${fitCountry.zoom} should be < city zoom ${fitCity.zoom}",
            fitCountry.zoom < fitCity.zoom,
        )
    }

    @Test
    fun `fuzz - zoom is always finite and clamped, small views always defer`() {
        val rnd = Random(42)
        repeat(3000) {
            val lat1 = rnd.nextDouble() * 170.0 - 85.0
            val lat2 = rnd.nextDouble() * 170.0 - 85.0
            val lng1 = rnd.nextDouble() * 360.0 - 180.0
            val lng2 = rnd.nextDouble() * 360.0 - 180.0
            val b = Bounds(
                minOf(lat1, lat2), maxOf(lat1, lat2),
                minOf(lng1, lng2), maxOf(lng1, lng2),
            )
            val w = rnd.nextInt(4000)
            val h = rnd.nextInt(4000)
            val fit = MapFit.fitForBounds(b, w, h, border, minZoom, maxZoom)
            if (w > 2 * border && h > 2 * border) {
                assertNotNull("should fit ${w}x$h for $b", fit)
                assertTrue("zoom finite for $b: ${fit!!.zoom}", fit.zoom.isFinite())
                assertTrue("zoom in range for $b: ${fit.zoom}", fit.zoom in minZoom..maxZoom)
            } else {
                assertNull("should defer ${w}x$h", fit)
            }
        }
    }
}
