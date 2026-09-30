package com.journeyvisualizer.app.animation

import org.junit.Assert.assertEquals
import org.junit.Test
private fun testEngine(
    speed: Double = 1.0,
    config: AnimationConfig = testConfig,
): AnimationEngine {
    val tl = AnimationTimeline.build(
        listOf(
            rp(31.5, 74.3, 0L, "Lahore", "Pakistan"),
            rp(33.7, 73.1, 3_600_000L, "Islamabad", "Pakistan"),
            rp(24.8, 67.0, 7_200_000L, "Karachi", "Pakistan"),
        ),
        config,
    )!!
    return AnimationEngine(tl, clockMs = { 0L }).also { it.setSpeed(speed) }
}

class AnimationEngineTest {

    @Test
    fun `initial state is IDLE at the journey start`() {
        val e = testEngine()
        assertEquals(PlaybackState.IDLE, e.getCurrentState())
        assertEquals(0L, e.getCurrentPosition())
        val f = e.frame.value
        assertEquals(31.5, f.lat, 1e-12)
        assertEquals(0, f.eventId)
        assertEquals(0L, e.getCurrentTimestamp())
    }

    @Test
    fun `play advances the clock and pause freezes it`() {
        val e = testEngine()
        e.play()
        assertEquals(PlaybackState.PLAYING, e.getCurrentState())
        e.advanceForTest(1_000L)
        assertEquals(1_000L, e.getCurrentPosition())
        val pos = e.getCurrentPosition()
        e.pause()
        assertEquals(PlaybackState.PAUSED, e.getCurrentState())
        e.advanceForTest(5_000L)
        assertEquals(pos, e.getCurrentPosition()) // frozen
        // Resume continues from the same position.
        e.play()
        e.advanceForTest(500L)
        assertEquals(pos + 500L, e.getCurrentPosition())
    }

    @Test
    fun `speed scales time predictably`() {
        for ((speed, factor) in listOf(0.5 to 500L, 1.0 to 1_000L, 2.0 to 2_000L, 4.0 to 4_000L)) {
            val e = testEngine(speed = speed)
            e.play()
            e.advanceForTest(1_000L)
            assertEquals("speed=$speed", factor, e.getCurrentPosition())
        }
    }

    @Test
    fun `speed never skips points`() {
        val e = testEngine(speed = 8.0)
        e.play()
        // Walk the whole journey in small wall-clock steps at 8x: every
        // segment must be visited in order — none skipped.
        val visited = LinkedHashSet<Int>()
        repeat(2000) {
            if (e.getCurrentState() != PlaybackState.PLAYING) return@repeat
            e.advanceForTest(10L)
            visited.add(e.frame.value.segmentIndex)
        }
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
        assertEquals(listOf(0, 1), visited.toList())
    }

    @Test
    fun `setSpeed rejects non-positive and non-finite values`() {
        val e = testEngine()
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                e.setSpeed(bad)
                throw AssertionError("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `completion parks the marker at the final coordinate`() {
        val e = testEngine()
        e.play()
        e.advanceForTest(1_000_000L)
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
        assertEquals(e.getTotalDuration(), e.getCurrentPosition())
        val f = e.frame.value
        assertEquals(24.8, f.lat, 1e-9)
        assertEquals(67.0, f.lng, 1e-9)
        assertEquals(7_200_000L, e.getCurrentTimestamp())
        assertEquals("Karachi", e.getCurrentEvent().city)
        // No auto-restart: further advances change nothing.
        e.advanceForTest(10_000L)
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
    }

    @Test
    fun `play after completion replays from the start`() {
        val e = testEngine()
        e.play()
        e.advanceForTest(1_000_000L)
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
        e.play()
        assertEquals(PlaybackState.PLAYING, e.getCurrentState())
        assertEquals(0L, e.getCurrentPosition())
    }

    @Test
    fun `stop resets to the beginning and keeps the data`() {
        val e = testEngine()
        e.play()
        e.advanceForTest(2_000L)
        e.stop()
        assertEquals(PlaybackState.STOPPED, e.getCurrentState())
        assertEquals(0L, e.getCurrentPosition())
        assertEquals(31.5, e.frame.value.lat, 1e-12)
        assertEquals(3, e.timeline.eventCount) // data untouched
        e.play()
        assertEquals(PlaybackState.PLAYING, e.getCurrentState())
    }

    @Test
    fun `restart goes back to zero and plays`() {
        val e = testEngine()
        e.play()
        e.advanceForTest(2_000L)
        e.restart()
        assertEquals(PlaybackState.PLAYING, e.getCurrentState())
        assertEquals(0L, e.getCurrentPosition())
    }

    @Test
    fun `seekTo clamps and lands on the right segment`() {
        val e = testEngine()
        e.seekTo(-500L)
        assertEquals(0L, e.getCurrentPosition())
        val s1 = e.timeline.segments[1]
        e.seekTo(s1.startAnimMs + 10)
        assertEquals(1, e.frame.value.segmentIndex)
        assertEquals(1, e.frame.value.eventId)
        e.seekTo(Long.MAX_VALUE)
        assertEquals(e.getTotalDuration(), e.getCurrentPosition())
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
    }

    @Test
    fun `seekTo preserves play state`() {
        val e = testEngine()
        e.play()
        e.advanceForTest(100L)
        e.seekTo(50L)
        assertEquals(PlaybackState.PLAYING, e.getCurrentState())
        assertEquals(50L, e.getCurrentPosition())
        e.pause()
        e.seekTo(60L)
        assertEquals(PlaybackState.PAUSED, e.getCurrentState())
    }

    @Test
    fun `previous and next navigate by stable event id`() {
        val e = testEngine()
        e.nextEvent()
        assertEquals(1, e.frame.value.eventId)
        assertEquals("Islamabad", e.getCurrentEvent().city)
        e.nextEvent()
        assertEquals(2, e.frame.value.eventId)
        e.nextEvent() // at the end: stays / completes
        assertEquals(2, e.frame.value.eventId)
        e.previousEvent()
        assertEquals(1, e.frame.value.eventId)
        e.previousEvent()
        assertEquals(0, e.frame.value.eventId)
        e.previousEvent() // at the start: stays
        assertEquals(0, e.frame.value.eventId)
    }

    @Test
    fun `seekToEvent uses stable ids not positions`() {
        // Shuffled input: ids do not match chronological positions.
        val tl = AnimationTimeline.build(
            listOf(
                rp(24.8, 67.0, 7_200_000L, "Karachi", "Pakistan"), // id 0, last
                rp(31.5, 74.3, 0L, "Lahore", "Pakistan"), // id 1, first
                rp(33.7, 73.1, 3_600_000L, "Islamabad", "Pakistan"), // id 2, middle
            ),
            testConfig,
        )!!
        val e = AnimationEngine(tl, clockMs = { 0L })
        e.seekToEvent(2)
        assertEquals(2, e.frame.value.eventId)
        assertEquals("Islamabad", e.getCurrentEvent().city)
        e.seekToEvent(999) // unknown id: ignored
        assertEquals(2, e.frame.value.eventId)
    }

    @Test
    fun `single-point timeline completes immediately on play`() {
        val tl = AnimationTimeline.build(
            listOf(rp(31.5, 74.3, 0L, "Lahore", "Pakistan")),
            testConfig,
        )!!
        val e = AnimationEngine(tl, clockMs = { 0L })
        e.play()
        assertEquals(PlaybackState.COMPLETED, e.getCurrentState())
        assertEquals(31.5, e.frame.value.lat, 1e-12)
    }

    @Test
    fun `follow mode toggles`() {
        val e = testEngine()
        assertEquals(CameraFollowMode.FOLLOW, e.followMode.value)
        e.setFollowMode(CameraFollowMode.FREE)
        assertEquals(CameraFollowMode.FREE, e.followMode.value)
        e.setFollowMode(CameraFollowMode.FOLLOW)
        assertEquals(CameraFollowMode.FOLLOW, e.followMode.value)
    }

    @Test
    fun `pause when not playing is a no-op`() {
        val e = testEngine()
        e.pause()
        assertEquals(PlaybackState.IDLE, e.getCurrentState())
        e.play()
        e.pause()
        e.pause()
        assertEquals(PlaybackState.PAUSED, e.getCurrentState())
    }

    @Test
    fun `deterministic playback from the same operations`() {
        fun run(): AnimationFrameState {
            val e = testEngine(speed = 2.0)
            e.play()
            e.advanceForTest(300L)
            e.seekTo(150L)
            e.advanceForTest(700L)
            e.pause()
            return e.frame.value
        }
        assertEquals(run(), run())
    }
}
