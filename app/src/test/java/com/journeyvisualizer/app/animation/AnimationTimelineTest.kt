package com.journeyvisualizer.app.animation

import com.journeyvisualizer.app.data.geo.MatchQuality
import com.journeyvisualizer.app.data.geo.ResolvedLocation
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.data.model.TrackPoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared builders for animation tests. */
internal fun rp(
    lat: Double,
    lng: Double,
    timeMs: Long,
    name: String? = null,
    country: String? = null,
): ResolvedPoint = ResolvedPoint(
    TrackPoint(lat, lng, timeMs),
    if (name == null) unresolvedLocation(lat, lng)
    else ResolvedLocation(lat, lng, name, country, 1.0, MatchQuality.CLOSE),
)

internal val testConfig = AnimationConfig(
    targetTotalMs = 10_000L,
    minSegmentMs = 100L,
    maxSegmentMs = 5_000L,
    dwellMs = 500L,
)

class AnimationMathTest {

    @Test
    fun `slerp returns exact endpoints at 0 and 1`() {
        val (la0, ln0) = AnimationMath.slerp(31.5, 74.3, 40.7, -74.0, 0.0)
        assertEquals(31.5, la0, 1e-12)
        assertEquals(74.3, ln0, 1e-12)
        val (la1, ln1) = AnimationMath.slerp(31.5, 74.3, 40.7, -74.0, 1.0)
        assertEquals(40.7, la1, 1e-9)
        assertEquals(-74.0, ln1, 1e-9)
    }

    @Test
    fun `slerp midpoint of short hop is the arithmetic midpoint`() {
        val (lat, lng) = AnimationMath.slerp(0.0, 0.0, 0.0, 90.0, 0.5)
        assertEquals(0.0, lat, 1e-9)
        assertEquals(45.0, lng, 1e-9)
    }

    @Test
    fun `slerp long jump follows the great circle`() {
        // Lahore -> New York: the great-circle midpoint must halve the distance.
        val total = AnimationMath.distanceM(31.5, 74.3, 40.7, -74.0)
        val (lat, lng) = AnimationMath.slerp(31.5, 74.3, 40.7, -74.0, 0.5)
        val half = AnimationMath.distanceM(31.5, 74.3, lat, lng)
        assertEquals(total / 2, half, total * 0.001)
        // The great-circle midpoint arcs toward the pole (near Scandinavia),
        // far from the naive straight-line midpoint (36.1, 0.15) — this is
        // the visually-correct long-haul behavior we want.
        assertTrue("mid lat=$lat", lat > 60.0)
    }

    @Test
    fun `slerp crosses the antimeridian the short way`() {
        val (lat, lng) = AnimationMath.slerp(10.0, 179.0, 10.0, -179.0, 0.5)
        // Great-circle bulge toward the pole is real; the key property is
        // the 2-degree hop goes through 180, not all the way around.
        assertEquals(10.0, lat, 0.01)
        assertEquals(180.0, abs(lng), 1e-6)
    }

    @Test
    fun `slerp clamps fraction`() {
        val (lat, _) = AnimationMath.slerp(0.0, 0.0, 10.0, 10.0, -0.5)
        assertEquals(0.0, lat, 1e-12)
    }
}

class AnimationTimelineTest {

    @Test
    fun `build sorts chronologically and keeps stable ids`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(40.0, -74.0, 3000L, "C", "USA"),
                rp(31.5, 74.3, 1000L, "A", "Pakistan"),
                rp(51.5, -0.1, 2000L, "B", "UK"),
            ),
            testConfig,
        )!!
        assertEquals(listOf(1, 2, 0), tl.points.map { it.id })
        assertEquals(listOf(1000L, 2000L, 3000L), tl.points.map { it.timestampMs })
    }

    @Test
    fun `duplicate timestamps keep file order (stable sort)`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(1.0, 1.0, 1000L),
                rp(2.0, 2.0, 1000L),
                rp(3.0, 3.0, 1000L),
            ),
            testConfig,
        )!!
        assertEquals(listOf(0, 1, 2), tl.points.map { it.id })
        // Zero real durations still get deterministic animation time.
        assertEquals(2, tl.segments.size)
        assertTrue(tl.segments.all { it.realDurationMs == 0L })
        assertTrue(tl.totalDurationMs > 0)
    }

    @Test
    fun `segment generation carries real and compressed durations`() {
        val tl = AnimationTimeline.build(
            listOf(rp(31.5, 74.3, 0L, "Lahore", "Pakistan"), rp(33.7, 73.1, 3_600_000L, "Islamabad", "Pakistan")),
            testConfig,
        )!!
        assertEquals(1, tl.segments.size)
        val seg = tl.segments[0]
        assertEquals(31.5, seg.from.lat, 1e-12)
        assertEquals(33.7, seg.to.lat, 1e-12)
        assertEquals(3_600_000L, seg.realDurationMs)
        assertTrue(seg.animDurationMs in 100L..5_000L)
        assertEquals(0L, seg.startAnimMs)
        assertEquals(seg.animDurationMs, seg.endAnimMs)
        assertTrue(seg.distanceM > 200_000) // Lahore->Islamabad ~270km
        assertFalse(seg.isDwell)
        assertEquals(tl.totalDurationMs, tl.segments.sumOf { it.animDurationMs })
    }

    @Test
    fun `compression is proportional within min-max bounds`() {
        val cfg = AnimationConfig(
            targetTotalMs = 120_000L, minSegmentMs = 100L, maxSegmentMs = 200_000L,
        )
        val tl = AnimationTimeline.build(
            listOf(
                rp(0.0, 0.0, 0L),
                rp(1.0, 1.0, 3_600_000L), // 1h
                rp(2.0, 2.0, 14_400_000L), // +3h
            ),
            cfg,
        )!!
        // 1:3 real ratio -> 1:3 animation ratio, no clamping involved.
        assertEquals(30_000L, tl.segments[0].animDurationMs)
        assertEquals(90_000L, tl.segments[1].animDurationMs)
    }

    @Test
    fun `min and max segment clamping`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(0.0, 0.0, 0L),
                rp(0.001, 0.0, 1L), // 1ms real -> min clamp
                rp(50.0, 50.0, 30L * 24 * 3_600_000L), // 30 days -> max clamp
            ),
            testConfig,
        )!!
        assertEquals(100L, tl.segments[0].animDurationMs)
        assertEquals(5_000L, tl.segments[1].animDurationMs)
    }

    @Test
    fun `duplicate coordinates become a dwell with no marker movement`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(31.5, 74.3, 0L, "Lahore", "Pakistan"),
                rp(31.5, 74.3, 3_600_000L, "Lahore", "Pakistan"),
                rp(33.7, 73.1, 7_200_000L, "Islamabad", "Pakistan"),
            ),
            testConfig,
        )!!
        val dwell = tl.segments[0]
        assertTrue(dwell.isDwell)
        assertEquals(500L, dwell.animDurationMs)
        // Marker stays put while animation time advances through the dwell.
        val mid = tl.stateAt(dwell.startAnimMs + dwell.animDurationMs / 2)
        assertEquals(31.5, mid.lat, 1e-12)
        assertEquals(74.3, mid.lng, 1e-12)
        // ...but the displayed clock keeps moving between the real timestamps.
        assertTrue(mid.displayedTimestampMs in 1L until 3_600_000L)
        // The second segment actually travels.
        assertFalse(tl.segments[1].isDwell)
    }

    @Test
    fun `invalid coordinates are skipped but valid neighbors animate`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(31.5, 74.3, 0L, "Lahore", "Pakistan"),
                rp(200.0, 74.3, 1_000L), // invalid latitude
                rp(Double.NaN, 10.0, 2_000L), // NaN
                rp(33.7, 73.1, 3_000L, "Islamabad", "Pakistan"),
            ),
            testConfig,
        )!!
        assertEquals(2, tl.points.size)
        assertEquals(listOf(0, 3), tl.points.map { it.id })
        assertEquals(1, tl.segments.size)
    }

    @Test
    fun `build returns null when nothing is mappable`() {
        assertNull(AnimationTimeline.build(listOf(rp(200.0, 0.0, 0L)), testConfig))
        assertNull(AnimationTimeline.build(emptyList(), testConfig))
    }

    @Test
    fun `single point timeline is a valid degenerate animation`() {
        val tl = AnimationTimeline.build(
            listOf(rp(31.5, 74.3, 12345L, "Lahore", "Pakistan")),
            testConfig,
        )!!
        assertEquals(0L, tl.totalDurationMs)
        val frame = tl.stateAt(999L)
        assertEquals(31.5, frame.lat, 1e-12)
        assertEquals(12345L, frame.displayedTimestampMs)
        assertEquals(0, frame.eventId)
    }

    @Test
    fun `seeking hits exact endpoints and correct segments`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(0.0, 0.0, 0L),
                rp(10.0, 0.0, 1_000L),
                rp(20.0, 0.0, 2_000L),
            ),
            testConfig,
        )!!
        val start = tl.stateAt(0L)
        assertEquals(0.0, start.lat, 1e-12)
        assertEquals(0, start.eventId)
        assertEquals(0L, start.displayedTimestampMs)

        val end = tl.stateAt(tl.totalDurationMs)
        assertEquals(20.0, end.lat, 1e-9)
        assertEquals(2, end.eventId)
        assertEquals(2_000L, end.displayedTimestampMs)
        assertNull(end.nextPoint)

        // Arbitrary mid-animation position lands in the right segment.
        val s1 = tl.segments[1]
        val mid = tl.stateAt(s1.startAnimMs + 1)
        assertEquals(1, mid.segmentIndex)
        assertEquals(1, mid.eventId) // segment's from-event while traveling
        assertEquals(2, mid.traveledPointCount)
    }

    @Test
    fun `displayed timestamp interpolates between real timestamps`() {
        val tl = AnimationTimeline.build(
            listOf(rp(0.0, 0.0, 1_000L), rp(10.0, 10.0, 3_000L)),
            testConfig,
        )!!
        val seg = tl.segments[0]
        val mid = tl.stateAt(seg.startAnimMs + seg.animDurationMs / 2)
        assertEquals(2_000L, mid.displayedTimestampMs)
    }

    @Test
    fun `build is deterministic`() {
        val input = listOf(
            rp(31.5, 74.3, 5_000L, "Lahore", "Pakistan"),
            rp(24.8, 67.0, 1_000L, "Karachi", "Pakistan"),
            rp(33.7, 73.1, 9_000L, "Islamabad", "Pakistan"),
            rp(31.5, 74.3, 9_000L, "Lahore", "Pakistan"),
        )
        val a = AnimationTimeline.build(input, testConfig)!!
        val b = AnimationTimeline.build(input, testConfig)!!
        assertEquals(a.points, b.points)
        assertEquals(a.segments, b.segments)
        assertEquals(a.totalDurationMs, b.totalDurationMs)
        // ...and every frame lookup agrees.
        var t = 0L
        while (t <= a.totalDurationMs) {
            assertEquals(a.stateAt(t), b.stateAt(t))
            t += 137L
        }
    }

    @Test
    fun `large international jump keeps real endpoints`() {
        val tl = AnimationTimeline.build(
            listOf(
                rp(31.5, 74.3, 0L, "Lahore", "Pakistan"),
                rp(25.2, 55.3, 10_000L, "Dubai", "UAE"),
                rp(51.5, -0.1, 20_000L, "London", "UK"),
            ),
            testConfig,
        )!!
        assertEquals(2, tl.segments.size)
        assertTrue(tl.segments[0].distanceM > 1_000_000)
        // The marker never teleports: endpoints are exact, mid is between.
        val seg = tl.segments[1]
        val mid = tl.stateAt(seg.startAnimMs + seg.animDurationMs / 2)
        assertTrue(mid.lat in 25.2..51.5)
        val atStart = tl.stateAt(seg.startAnimMs)
        assertEquals(25.2, atStart.lat, 1e-9)
        assertEquals(55.3, atStart.lng, 1e-9)
    }

    @Test
    fun `original event stays reachable from animation points`() {
        val input = listOf(rp(31.5, 74.3, 0L, "Lahore", "Pakistan"))
        val tl = AnimationTimeline.build(input, testConfig)!!
        assertTrue(tl.points[0].source === input[0])
        assertEquals(74.3, tl.points[0].source.point.lng, 1e-12)
    }

    @Test
    fun `segmentIndexAt handles every boundary`() {
        val tl = AnimationTimeline.build(
            listOf(rp(0.0, 0.0, 0L), rp(1.0, 1.0, 1_000L), rp(2.0, 2.0, 2_000L)),
            testConfig,
        )!!
        val s0 = tl.segments[0]
        val s1 = tl.segments[1]
        assertEquals(0, tl.segmentIndexAt(0L))
        assertEquals(1, tl.segmentIndexAt(s0.endAnimMs)) // boundary -> next segment
        assertEquals(0, tl.segmentIndexAt(s0.endAnimMs - 1))
        assertEquals(1, tl.segmentIndexAt(tl.totalDurationMs))
        assertEquals(-1, AnimationTimeline.build(listOf(rp(0.0, 0.0, 0L)), testConfig)!!
            .segmentIndexAt(0L))
    }
}
