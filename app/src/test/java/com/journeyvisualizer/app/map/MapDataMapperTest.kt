package com.journeyvisualizer.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 4 map data layer.
 *
 * Covers §24: coordinates (valid/invalid/missing), route chronological
 * ordering, bounds (single/two/local/international), GeoNames integration
 * (raw coordinate preserved), and selection index stability.
 */
class MapDataMapperTest {

    private fun resolved(
        lat: Double,
        lng: Double,
        timeMs: Long,
        name: String? = "Place",
    ): com.journeyvisualizer.app.data.geo.ResolvedPoint {
        val loc = if (name == null) {
            com.journeyvisualizer.app.data.geo.unresolvedLocation(lat, lng)
        } else {
            com.journeyvisualizer.app.data.geo.ResolvedLocation(
                latitude = lat,
                longitude = lng,
                name = name,
                country = "Testland",
                distanceKm = 1.0,
                matchQuality = com.journeyvisualizer.app.data.geo.MatchQuality.CLOSE,
            )
        }
        return com.journeyvisualizer.app.data.geo.ResolvedPoint(
            point = com.journeyvisualizer.app.data.model.TrackPoint(lat, lng, timeMs),
            location = loc,
        )
    }

    // -- Coordinates --------------------------------------------------------

    @Test fun `valid coordinates are mappable`() {
        val p = MapPoint(0, 31.5204, 74.3587, 1000L, null)
        assertTrue(p.isMappable)
    }

    @Test fun `invalid latitude is not mappable`() {
        assertFalse(MapPoint(0, 91.0, 0.0, 0L, null).isMappable)
        assertFalse(MapPoint(0, -91.0, 0.0, 0L, null).isMappable)
    }

    @Test fun `invalid longitude is not mappable`() {
        assertFalse(MapPoint(0, 0.0, 181.0, 0L, null).isMappable)
        assertFalse(MapPoint(0, 0.0, -181.0, 0L, null).isMappable)
    }

    @Test fun `NaN coordinates are not mappable`() {
        assertFalse(MapPoint(0, Double.NaN, 74.0, 0L, null).isMappable)
        assertFalse(MapPoint(0, 31.0, Double.NaN, 0L, null).isMappable)
    }

    @Test fun `poles beyond web mercator are not mappable`() {
        assertFalse(MapPoint(0, 86.0, 0.0, 0L, null).isMappable)
    }

    @Test fun `mappable filters invalid points`() {
        val pts = listOf(
            MapPoint(0, 31.5, 74.3, 1L, null),
            MapPoint(1, 999.0, 74.3, 2L, null),
            MapPoint(2, 32.5, 75.3, 3L, null),
        )
        val mappable = pts.mappable()
        assertEquals(2, mappable.size)
        assertEquals(listOf(0, 2), mappable.map { it.index })
    }

    // -- Raw coordinate preservation -----------------------------------------

    @Test fun `raw timeline coordinates are preserved, not city-center coords`() {
        // A coordinate near — but not exactly at — Lahore's city center.
        val rawLat = 31.5204
        val rawLng = 74.3587
        val pts = listOf(resolved(rawLat, rawLng, 1000L, "Lahore")).toMapPoints()
        assertEquals(1, pts.size)
        assertEquals(rawLat, pts[0].lat, 1e-9)
        assertEquals(rawLng, pts[0].lng, 1e-9)
        assertEquals("Lahore", pts[0].location?.name)
    }

    @Test fun `chronological order is preserved`() {
        val pts = listOf(
            resolved(31.5, 74.3, 3000L, "C"),
            resolved(32.5, 75.3, 1000L, "A"),
            resolved(33.5, 76.3, 2000L, "B"),
        ).toMapPoints()
        // Mapper does not reorder; the parser guarantees chronological input.
        assertEquals(listOf(3000L, 1000L, 2000L), pts.map { it.timeMs })
    }

    @Test fun `indexes are stable timeline ids`() {
        val pts = listOf(
            resolved(31.5, 74.3, 1L),
            resolved(999.0, 74.3, 2L), // invalid, kept with its index
            resolved(33.5, 76.3, 3L),
        ).toMapPoints()
        assertEquals(listOf(0, 1, 2), pts.map { it.index })
    }

    @Test fun `unresolved points keep raw coordinates`() {
        val pts = listOf(resolved(0.0, -140.0, 1L, null)).toMapPoints()
        assertEquals(0.0, pts[0].lat, 1e-9)
        assertEquals(-140.0, pts[0].lng, 1e-9)
        assertEquals(
            com.journeyvisualizer.app.data.geo.MatchQuality.UNRESOLVED,
            pts[0].location?.matchQuality,
        )
    }

    // -- Bounds ---------------------------------------------------------------

    @Test fun `empty points give null bounds`() {
        assertNull(MapDataMapper.boundsOf(emptyList()))
    }

    @Test fun `all-invalid points give null bounds`() {
        val pts = listOf(MapPoint(0, 999.0, 0.0, 1L, null))
        assertNull(MapDataMapper.boundsOf(pts))
    }

    @Test fun `single point bounds collapse`() {
        val pts = listOf(resolved(31.5204, 74.3587, 1L)).toMapPoints()
        val b = MapDataMapper.boundsOf(pts)!!
        assertEquals(31.5204, b.minLat, 1e-9)
        assertEquals(31.5204, b.maxLat, 1e-9)
        assertEquals(74.3587, b.minLng, 1e-9)
        assertEquals(74.3587, b.maxLng, 1e-9)
    }

    @Test fun `two-point bounds`() {
        val pts = listOf(
            resolved(31.5204, 74.3587, 1L), // Lahore
            resolved(51.5074, -0.1278, 2L), // London
        ).toMapPoints()
        val b = MapDataMapper.boundsOf(pts)!!
        assertEquals(31.5204, b.minLat, 1e-9)
        assertEquals(51.5074, b.maxLat, 1e-9)
        assertEquals(-0.1278, b.minLng, 1e-9)
        assertEquals(74.3587, b.maxLng, 1e-9)
    }

    @Test fun `international journey bounds span continents`() {
        val pts = listOf(
            resolved(31.5204, 74.3587, 1L, "Lahore"),
            resolved(51.5074, -0.1278, 2L, "London"),
            resolved(40.7128, -74.0060, 3L, "New York"),
            resolved(35.6762, 139.6503, 4L, "Tokyo"),
            resolved(-33.8688, 151.2093, 5L, "Sydney"),
        ).toMapPoints()
        val b = MapDataMapper.boundsOf(pts)!!
        assertEquals(-33.8688, b.minLat, 1e-9)
        assertEquals(51.5074, b.maxLat, 1e-9)
        assertEquals(-74.0060, b.minLng, 1e-9)
        assertEquals(151.2093, b.maxLng, 1e-9)
    }

    @Test fun `duplicate coordinates do not break bounds`() {
        val pts = listOf(
            resolved(31.5, 74.3, 1L),
            resolved(31.5, 74.3, 2L),
            resolved(31.5, 74.3, 3L),
        ).toMapPoints()
        val b = MapDataMapper.boundsOf(pts)!!
        assertEquals(31.5, b.minLat, 1e-9)
        assertEquals(31.5, b.maxLat, 1e-9)
    }

    // -- Decimation ------------------------------------------------------------

    @Test fun `decimation keeps first and last and caps size`() {
        val pts = (0 until 10_000).map { i ->
            MapPoint(i, 30.0 + i * 0.0001, 74.0, i.toLong(), null)
        }
        val dec = MapDataMapper.decimateChronological(pts, 4000)
        assertTrue(dec.size <= 4000)
        assertEquals(0, dec.first().index)
        assertEquals(9_999, dec.last().index)
        // Chronological order preserved.
        assertTrue(dec.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }

    @Test fun `decimation is a no-op under the cap`() {
        val pts = (0 until 100).map { i -> MapPoint(i, 30.0, 74.0, i.toLong(), null) }
        assertEquals(pts, MapDataMapper.decimateChronological(pts, 4000))
    }
}
