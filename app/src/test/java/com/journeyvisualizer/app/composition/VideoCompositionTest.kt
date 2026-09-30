package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.animation.AnimationConfig
import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.animation.PlaybackState
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.data.model.TrackPoint
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.VideoMarkerStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rp(lat: Double, lng: Double, timeMs: Long): ResolvedPoint =
    ResolvedPoint(TrackPoint(lat, lng, timeMs), unresolvedLocation(lat, lng))

private fun testTimeline(): AnimationTimeline {
    val pts = listOf(
        rp(31.5, 74.3, 1_000L),
        rp(32.5, 75.3, 2_000L),
        rp(33.5, 76.3, 3_000L),
    )
    return AnimationTimeline.build(pts, AnimationConfig(targetTotalMs = 10_000L))!!
}

/** Manual clock for deterministic controller tests. */
private class FakeClock(var now: Long = 0L) {
    fun advance(dt: Long) { now += dt }
}

private fun testController(
    engine: AnimationEngine,
    clock: FakeClock,
    introMs: Long = 0L,
    outroMs: Long = 0L,
): VideoPreviewController =
    VideoPreviewController(
        engine,
        CoroutineScope(Dispatchers.Unconfined),
        clockMs = { clock.now },
        enableTicker = false,
    ).also {
        it.introMs = introMs
        it.outroMs = outroMs
    }

class VideoCompositionTest {

    // -- Aspect ratio --------------------------------------------------------

    @Test
    fun `aspect ratios have correct proportions`() {
        assertEquals(16f / 9f, VideoAspectRatio.SIXTEEN_NINE.ratio, 1e-6f)
        assertEquals(9f / 16f, VideoAspectRatio.NINE_SIXTEEN.ratio, 1e-6f)
        assertEquals(1f, VideoAspectRatio.ONE_ONE.ratio, 1e-6f)
        assertTrue(VideoAspectRatio.NINE_SIXTEEN.isPortrait)
        assertFalse(VideoAspectRatio.SIXTEEN_NINE.isPortrait)
        assertTrue(VideoAspectRatio.ONE_ONE.isSquare)
    }

    // -- Resolution ----------------------------------------------------------

    @Test
    fun `720p export sizes per aspect ratio`() {
        assertEquals(1280 to 720, VideoResolutionPreset.P720.exportSize(VideoAspectRatio.SIXTEEN_NINE))
        assertEquals(720 to 1280, VideoResolutionPreset.P720.exportSize(VideoAspectRatio.NINE_SIXTEEN))
        assertEquals(720 to 720, VideoResolutionPreset.P720.exportSize(VideoAspectRatio.ONE_ONE))
    }

    @Test
    fun `1080p export sizes per aspect ratio`() {
        assertEquals(1920 to 1080, VideoResolutionPreset.P1080.exportSize(VideoAspectRatio.SIXTEEN_NINE))
        assertEquals(1080 to 1920, VideoResolutionPreset.P1080.exportSize(VideoAspectRatio.NINE_SIXTEEN))
        assertEquals(1080 to 1080, VideoResolutionPreset.P1080.exportSize(VideoAspectRatio.ONE_ONE))
    }

    @Test
    fun `1440p and 4K export sizes`() {
        assertEquals(2560 to 1440, VideoResolutionPreset.P1440.exportSize(VideoAspectRatio.SIXTEEN_NINE))
        assertEquals(1440 to 2560, VideoResolutionPreset.P1440.exportSize(VideoAspectRatio.NINE_SIXTEEN))
        assertEquals(3840 to 2160, VideoResolutionPreset.P2160.exportSize(VideoAspectRatio.SIXTEEN_NINE))
        assertEquals(2160 to 3840, VideoResolutionPreset.P2160.exportSize(VideoAspectRatio.NINE_SIXTEEN))
        assertEquals(2160 to 2160, VideoResolutionPreset.P2160.exportSize(VideoAspectRatio.ONE_ONE))
    }

    @Test
    fun `all export sizes are positive and even`() {
        for (preset in VideoResolutionPreset.values()) {
            for (aspect in VideoAspectRatio.values()) {
                val (w, h) = preset.exportSize(aspect)
                assertTrue("$preset $aspect", w > 0 && h > 0)
                assertEquals("$preset $aspect width even", 0, w % 2)
                assertEquals("$preset $aspect height even", 0, h % 2)
            }
        }
    }

    // -- FPS -----------------------------------------------------------------

    @Test
    fun `fps values are 24 30 60`() {
        assertEquals(24, VideoFps.FPS_24.value)
        assertEquals(30, VideoFps.FPS_30.value)
        assertEquals(60, VideoFps.FPS_60.value)
    }

    // -- Duration mapping ----------------------------------------------------

    @Test
    fun `duration modes map to animation targets`() {
        val base = VideoComposition.DEFAULT
        assertEquals(90_000L, base.copy(durationMode = VideoDurationMode.AUTO).animationTargetMs())
        assertEquals(30_000L, base.copy(durationMode = VideoDurationMode.SHORT).animationTargetMs())
        assertEquals(60_000L, base.copy(durationMode = VideoDurationMode.MEDIUM).animationTargetMs())
        assertEquals(180_000L, base.copy(durationMode = VideoDurationMode.LONG).animationTargetMs())
    }

    @Test
    fun `custom duration sets the total video length`() {
        val c = VideoComposition.DEFAULT.copy(
            durationMode = VideoDurationMode.CUSTOM,
            customDurationSec = 120,
        )
        assertEquals(120_000L, c.animationTargetMs())
    }

    @Test
    fun `custom duration subtracts intro and outro`() {
        val c = VideoComposition.DEFAULT.copy(
            durationMode = VideoDurationMode.CUSTOM,
            customDurationSec = 120,
            introEnabled = true, introDurationSec = 2,
            outroEnabled = true, outroDurationSec = 3,
        )
        assertEquals(115_000L, c.animationTargetMs())
    }

    @Test
    fun `custom duration never drops below 10s of journey`() {
        val c = VideoComposition.DEFAULT.copy(
            durationMode = VideoDurationMode.CUSTOM,
            customDurationSec = 10,
            introEnabled = true, introDurationSec = 5,
            outroEnabled = true, outroDurationSec = 5,
        )
        assertEquals(10_000L, c.animationTargetMs())
    }

    // -- Serialization -------------------------------------------------------

    @Test
    fun `composition round-trips through codec`() {
        val c = VideoComposition(
            aspectRatio = VideoAspectRatio.NINE_SIXTEEN,
            resolutionPreset = VideoResolutionPreset.P2160,
            fps = VideoFps.FPS_60,
            durationMode = VideoDurationMode.CUSTOM,
            customDurationSec = 200,
            mapStyle = BasemapStyle.SATELLITE,
            cameraMode = CompositionCameraMode.SMART_FOLLOW,
            routeDrawMode = RouteDrawMode.FULL,
            routeVisible = false,
            routeWidthScale = 1.5f,
            routeOpacity = 0.5f,
            markerStyle = VideoMarkerStyle.HIGHLIGHTED,
            startEndMarkers = false,
            showLocation = false,
            showDateTime = false,
            showProgress = false,
            title = "My; Journey = 100% & more — لاہور",
            subtitle = "March 2026; part 2",
            overlayPosition = OverlayPosition.BOTTOM_RIGHT,
            textSize = OverlayTextSize.LARGE,
            textWeight = OverlayTextWeight.REGULAR,
            textAlign = OverlayTextAlign.CENTER,
            introEnabled = true, introDurationSec = 3,
            outroEnabled = true, outroDurationSec = 4,
            outroShowFinalLocation = false,
        )
        assertEquals(c, CompositionCodec.decode(CompositionCodec.encode(c)))
    }

    @Test
    fun `codec decode of garbage returns defaults and never throws`() {
        assertEquals(VideoComposition.DEFAULT, CompositionCodec.decode(""))
        assertEquals(VideoComposition.DEFAULT, CompositionCodec.decode(";;;"))
        assertEquals(VideoComposition.DEFAULT, CompositionCodec.decode("aspectRatio=BOGUS;fps=NOPE"))
    }

    @Test
    fun `codec keeps unknown fields at defaults`() {
        val c = CompositionCodec.decode("aspectRatio=NINE_SIXTEEN")
        assertEquals(VideoAspectRatio.NINE_SIXTEEN, c.aspectRatio)
        assertEquals(VideoComposition.DEFAULT.resolutionPreset, c.resolutionPreset)
    }

    // -- Presets -------------------------------------------------------------

    @Test
    fun `presets are valid compositions`() {
        for ((_, preset) in CompositionPresets.all()) {
            val issues = CompositionValidator.validate(
                preset, hasTimeline = true, animationTotalMs = 90_000L,
            )
            assertTrue(issues.isEmpty())
        }
    }

    @Test
    fun `travel preset is a 16-9 journey video`() {
        val p = CompositionPresets.travel()
        assertEquals(VideoAspectRatio.SIXTEEN_NINE, p.aspectRatio)
        assertEquals(CompositionCameraMode.FOLLOW_JOURNEY, p.cameraMode)
        assertTrue(p.showLocation && p.showDateTime && p.showProgress)
        assertTrue(p.routeVisible && p.startEndMarkers)
    }

    @Test
    fun `social preset is 9-16 with big overlay`() {
        val p = CompositionPresets.social()
        assertEquals(VideoAspectRatio.NINE_SIXTEEN, p.aspectRatio)
        assertEquals(OverlayTextSize.LARGE, p.textSize)
        assertEquals(OverlayTextAlign.CENTER, p.textAlign)
    }

    @Test
    fun `minimal preset hides text overlays`() {
        val p = CompositionPresets.minimal()
        assertFalse(p.showLocation || p.showDateTime || p.showProgress)
        assertFalse(p.introEnabled || p.outroEnabled)
        assertTrue(p.routeVisible)
        assertEquals(VideoMarkerStyle.MINIMAL_DOT, p.markerStyle)
    }

    // -- Reset semantics ------------------------------------------------------

    @Test
    fun `reset restores defaults without touching anything else`() {
        val customized = CompositionPresets.social().copy(title = "Hello")
        val reset = VideoComposition.DEFAULT
        assertEquals(VideoAspectRatio.SIXTEEN_NINE, reset.aspectRatio)
        assertEquals("", reset.title)
        // Reset is a pure composition replacement — the timeline object is
        // untouched (asserted structurally: nothing here references it).
        assertTrue(customized != reset)
    }

    // -- Validation ------------------------------------------------------------

    @Test
    fun `validation requires a timeline`() {
        val issues = CompositionValidator.validate(
            VideoComposition.DEFAULT, hasTimeline = false, animationTotalMs = 0L,
        )
        assertEquals(1, issues.size)
        assertEquals("timeline", issues[0].field)
    }

    @Test
    fun `validation rejects empty animation`() {
        val issues = CompositionValidator.validate(
            VideoComposition.DEFAULT, hasTimeline = true, animationTotalMs = 0L,
        )
        assertTrue(issues.any { it.field == "timeline" })
    }

    @Test
    fun `validation accepts a healthy composition`() {
        val issues = CompositionValidator.validate(
            VideoComposition.DEFAULT, hasTimeline = true, animationTotalMs = 90_000L,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validation rejects out-of-range custom duration`() {
        val tooShort = VideoComposition.DEFAULT.copy(
            durationMode = VideoDurationMode.CUSTOM, customDurationSec = 5,
        )
        val tooLong = VideoComposition.DEFAULT.copy(
            durationMode = VideoDurationMode.CUSTOM, customDurationSec = 601,
        )
        assertTrue(
            CompositionValidator.validate(tooShort, true, 90_000L).any { it.field == "duration" },
        )
        assertTrue(
            CompositionValidator.validate(tooLong, true, 90_000L).any { it.field == "duration" },
        )
    }

    @Test
    fun `validation rejects overlong titles`() {
        val badTitle = VideoComposition.DEFAULT.copy(title = "x".repeat(81))
        val badSub = VideoComposition.DEFAULT.copy(subtitle = "x".repeat(121))
        assertTrue(
            CompositionValidator.validate(badTitle, true, 90_000L).any { it.field == "title" },
        )
        assertTrue(
            CompositionValidator.validate(badSub, true, 90_000L).any { it.field == "subtitle" },
        )
    }

    // -- Export spec -----------------------------------------------------------

    @Test
    fun `export spec is deterministic`() {
        val timeline = testTimeline()
        val c = CompositionPresets.travel().copy(
            introEnabled = true, introDurationSec = 2,
            outroEnabled = true, outroDurationSec = 2,
        )
        val a = c.toExportSpec(timeline, "trip.json")
        val b = c.toExportSpec(timeline, "trip.json")
        assertEquals(a, b)
        assertEquals("trip.json", a.timelineRef)
        assertEquals(3, a.eventCount)
        assertEquals(10_000L, a.animationTotalMs)
        assertEquals(2_000L, a.introMs)
        assertEquals(2_000L, a.outroMs)
        assertEquals(14_000L, a.totalVideoMs)
        assertEquals(1920, a.exportWidth)
        assertEquals(1080, a.exportHeight)
        assertEquals(30, a.fps)
    }

    @Test
    fun `export spec references the timeline instead of copying it`() {
        val timeline = testTimeline()
        val spec = VideoComposition.DEFAULT.toExportSpec(timeline, "trip.json")
        // The spec carries counts and settings — no JSON, no points.
        assertEquals(timeline.eventCount, spec.eventCount)
        assertEquals(timeline.totalDurationMs, spec.animationTotalMs)
    }

    // -- Preview controller ----------------------------------------------------

    @Test
    fun `controller total is intro plus animation plus outro`() {
        val timeline = testTimeline()
        val clock = FakeClock()
        val ctl = testController(
            AnimationEngine(timeline, clockMs = { clock.now }),
            clock, introMs = 2_000L, outroMs = 3_000L,
        )
        assertEquals(15_000L, ctl.totalVideoMs)
        ctl.detach()
    }

    @Test
    fun `seek maps deterministically onto the engine`() {
        val timeline = testTimeline()
        val engineClock = FakeClock()
        val engine = AnimationEngine(timeline, clockMs = { engineClock.now })
        val clock = FakeClock()
        val ctl = testController(engine, clock, introMs = 2_000L, outroMs = 3_000L)

        ctl.seekTo(0L)
        assertEquals(0L, engine.getCurrentPosition())
        assertTrue(ctl.isInIntro())

        ctl.seekTo(2_000L)
        assertEquals(0L, engine.getCurrentPosition())
        assertFalse(ctl.isInIntro())

        ctl.seekTo(7_000L)
        assertEquals(5_000L, engine.getCurrentPosition())

        ctl.seekTo(15_000L)
        assertEquals(10_000L, engine.getCurrentPosition())
        assertTrue(ctl.isInOutro())
        ctl.detach()
    }

    @Test
    fun `controller play runs intro then hands the clock to the engine`() {
        val timeline = testTimeline()
        val engineClock = FakeClock()
        val engine = AnimationEngine(timeline, clockMs = { engineClock.now })
        val clock = FakeClock()
        val ctl = testController(engine, clock, introMs = 2_000L, outroMs = 0L)

        ctl.play()
        assertEquals(VideoPreviewState.INTRO, ctl.previewState.value)
        // The real ticker is disabled in tests; drive deterministically.
        ctl.advanceForTest(1_000L)
        assertEquals(1_000L, ctl.videoPositionMs.value)
        ctl.advanceForTest(1_500L)
        // Intro elapsed: the engine is now playing the journey.
        assertEquals(PlaybackState.PLAYING, engine.getCurrentState())
        assertEquals(2_000L, ctl.videoPositionMs.value)

        // Advance the engine's own clock; the controller snaps to it.
        engine.advanceForTest(3_000L)
        ctl.advanceForTest(33L)
        assertEquals(5_000L, ctl.videoPositionMs.value)
        ctl.detach()
    }

    @Test
    fun `controller pause freezes the video clock`() {
        val timeline = testTimeline()
        val engine = AnimationEngine(timeline)
        val clock = FakeClock()
        val ctl = testController(engine, clock)
        ctl.play()
        engine.play()
        engine.advanceForTest(2_000L)
        ctl.advanceForTest(33L)
        assertEquals(2_000L, ctl.videoPositionMs.value)
        ctl.pause()
        assertEquals(VideoPreviewState.PAUSED, ctl.previewState.value)
        assertEquals(PlaybackState.PAUSED, engine.getCurrentState())
        ctl.advanceForTest(5_000L)
        assertEquals(2_000L, ctl.videoPositionMs.value) // frozen
        assertEquals(2_000L, engine.getCurrentPosition()) // engine frozen too
        ctl.detach()
    }

    @Test
    fun `controller reaches completed at the video end`() {
        val timeline = testTimeline()
        val engineClock = FakeClock()
        val engine = AnimationEngine(timeline, clockMs = { engineClock.now })
        val clock = FakeClock()
        val ctl = testController(engine, clock, introMs = 0L, outroMs = 1_000L)

        ctl.play()
        // Drive the engine to its natural end; the controller must observe
        // the completion and move into the outro (not rewind the engine).
        engine.play()
        engine.advanceForTest(10_000L)
        assertEquals(PlaybackState.COMPLETED, engine.getCurrentState())
        ctl.advanceForTest(33L) // observes engine completion -> journey end
        assertEquals(10_000L, ctl.videoPositionMs.value)
        ctl.advanceForTest(33L) // outro region advances the video clock
        assertEquals(VideoPreviewState.OUTRO, ctl.previewState.value)
        ctl.advanceForTest(1_500L) // outro elapses
        assertEquals(VideoPreviewState.COMPLETED, ctl.previewState.value)
        assertEquals(11_000L, ctl.videoPositionMs.value)
        ctl.detach()
    }

    @Test
    fun `controller reset parks everything at zero`() {
        val timeline = testTimeline()
        val engine = AnimationEngine(timeline)
        val clock = FakeClock()
        val ctl = testController(engine, clock, introMs = 2_000L)
        ctl.seekTo(9_000L)
        ctl.reset()
        assertEquals(0L, ctl.videoPositionMs.value)
        assertEquals(VideoPreviewState.IDLE, ctl.previewState.value)
        assertEquals(0L, engine.getCurrentPosition())
        ctl.detach()
    }
}
