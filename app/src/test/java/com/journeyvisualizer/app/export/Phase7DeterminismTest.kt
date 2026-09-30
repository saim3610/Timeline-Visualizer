package com.journeyvisualizer.app.export

import android.net.Uri
import com.journeyvisualizer.app.animation.AnimationConfig
import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.data.model.TrackPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// RenderClock — deterministic frame math.
// ---------------------------------------------------------------------------

class RenderClockTest {

    @Test
    fun totalFrames_90s_atCommonFps() {
        assertEquals(2700, RenderClock.totalFrames(90_000L, 30))
        assertEquals(2160, RenderClock.totalFrames(90_000L, 24))
        assertEquals(5400, RenderClock.totalFrames(90_000L, 60))
    }

    @Test
    fun totalFrames_roundsUpPartialInterval() {
        // 1001 ms @30fps spans 30.03 frame intervals → 31 frames.
        assertEquals(31, RenderClock.totalFrames(1_001L, 30))
        assertEquals(30, RenderClock.totalFrames(1_000L, 30))
    }

    @Test
    fun presentationTimestamps_areExactAndDriftFree() {
        assertEquals(0L, RenderClock.presentationTimeNs(0, 30))
        assertEquals(1_000_000_000L, RenderClock.presentationTimeNs(30, 30))
        // Spacing is exactly 1e9/fps — no accumulated float error.
        val step = RenderClock.presentationTimeNs(1, 30) - RenderClock.presentationTimeNs(0, 30)
        assertEquals(33_333_333L, step)
        val last = RenderClock.presentationTimeNs(2699, 30)
        assertEquals(2699L * 1_000_000_000L / 30, last)
    }

    @Test
    fun videoTimeMs_matchesFrameIndex() {
        assertEquals(0L, RenderClock.videoTimeMs(0, 30))
        assertEquals(1_000L, RenderClock.videoTimeMs(30, 30))
        assertEquals(89_966L, RenderClock.videoTimeMs(2699, 30))
    }

    @Test
    fun regionFor_mapsIntroJourneyOutro() {
        assertEquals(FrameRegion.INTRO, RenderClock.regionFor(0L, 2_000L, 92_000L))
        assertEquals(FrameRegion.INTRO, RenderClock.regionFor(1_999L, 2_000L, 92_000L))
        assertEquals(FrameRegion.JOURNEY, RenderClock.regionFor(2_000L, 2_000L, 92_000L))
        assertEquals(FrameRegion.JOURNEY, RenderClock.regionFor(91_999L, 2_000L, 92_000L))
        assertEquals(FrameRegion.OUTRO, RenderClock.regionFor(92_000L, 2_000L, 92_000L))
        // No intro: everything before journeyEnd is journey.
        assertEquals(FrameRegion.JOURNEY, RenderClock.regionFor(0L, 0L, 90_000L))
    }

    @Test
    fun frameSequence_replayIsIdentical() {
        // The export contract: same (index, fps) → same time, every run.
        val run1 = (0 until 300).map { RenderClock.videoTimeMs(it, 30) }
        val run2 = (0 until 300).map { RenderClock.videoTimeMs(it, 30) }
        assertEquals(run1, run2)
    }
}

// ---------------------------------------------------------------------------
// BitratePolicy.
// ---------------------------------------------------------------------------

class BitratePolicyTest {

    @Test
    fun bitrateFor_resolutionClasses() {
        assertEquals(4_000_000, BitratePolicy.bitrateFor(1280, 720, 30))
        assertEquals(8_000_000, BitratePolicy.bitrateFor(1920, 1080, 30))
        assertEquals(16_000_000, BitratePolicy.bitrateFor(2560, 1440, 30))
        assertEquals(32_000_000, BitratePolicy.bitrateFor(3840, 2160, 30))
    }

    @Test
    fun bitrateFor_portrait9x16_matchesLandscapeClass() {
        // 720x1280 = 921,600 px = same class as 1280x720.
        assertEquals(4_000_000, BitratePolicy.bitrateFor(720, 1280, 30))
        assertEquals(8_000_000, BitratePolicy.bitrateFor(1080, 1920, 30))
    }

    @Test
    fun bitrateFor_scalesWithFps() {
        assertEquals(8_000_000, BitratePolicy.bitrateFor(1280, 720, 60))
        assertEquals(3_200_000, BitratePolicy.bitrateFor(1280, 720, 24))
    }

    @Test
    fun coerceToCodecRange_clamps() {
        assertEquals(5_000_000, BitratePolicy.coerceToCodecRange(5_000_000, 1_000_000, 10_000_000))
        assertEquals(10_000_000, BitratePolicy.coerceToCodecRange(99_000_000, 1_000_000, 10_000_000))
        assertEquals(1_000_000, BitratePolicy.coerceToCodecRange(100, 1_000_000, 10_000_000))
    }
}

// ---------------------------------------------------------------------------
// RenderErrors.
// ---------------------------------------------------------------------------

class RenderErrorsTest {

    @Test
    fun unsupported4k_hasExactWording() {
        assertEquals(
            "4K export is not supported on this device.",
            RenderError.FourKUnsupported().userMessage,
        )
    }

    @Test
    fun mapToRenderError_passthroughAndMapping() {
        val direct = RenderException(RenderError.TilesUnavailable())
        assertEquals(RenderError.TilesUnavailable(), mapToRenderError(direct))
        assertEquals(RenderError.OutOfMemory, mapToRenderError(OutOfMemoryError()))
        assertEquals(RenderError.StorageFailure, mapToRenderError(java.io.IOException("disk")))
        assertEquals(RenderError.InvalidComposition, mapToRenderError(IllegalArgumentException("bad")))
        // CancellationException is never a failure state — it maps to
        // Unknown here because the service handles cancellation separately.
        assertTrue(mapToRenderError(CancellationException()) is RenderError.Unknown)
    }

    @Test
    fun mapToRenderError_unknownKeepsMessage() {
        val err = mapToRenderError(RuntimeException("boom"))
        assertTrue(err is RenderError.Unknown)
        assertTrue(err.userMessage.isNotBlank())
    }
}

// ---------------------------------------------------------------------------
// ExportFileNamer.
// ---------------------------------------------------------------------------

class ExportFileNamerTest {

    @Test
    fun baseName_hasExpectedShape() {
        val name = ExportFileNamer.baseName(1_790_000_000_000L)
        assertTrue(
            "unexpected name: $name",
            name.matches(Regex("Timeline_\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.mp4")),
        )
    }

    @Test
    fun uniqueName_noCollisionReturnsBase() {
        val base = "Timeline_2026-09-30_1200.mp4"
        assertEquals(base, ExportFileNamer.uniqueName(base) { false })
    }

    @Test
    fun uniqueName_collisionsGetNumberedSuffixes() {
        val taken = mutableSetOf(
            "Timeline_2026-09-30_1200.mp4",
            "Timeline_2026-09-30_1200 (2).mp4",
        )
        val unique = ExportFileNamer.uniqueName("Timeline_2026-09-30_1200.mp4") { it in taken }
        assertEquals("Timeline_2026-09-30_1200 (3).mp4", unique)
    }
}

// ---------------------------------------------------------------------------
// RenderProgress + RenderState + ExportProgressBus.
// ---------------------------------------------------------------------------

class RenderProgressTest {

    @Test
    fun fraction_derivesFromCompletedFrames() {
        assertEquals(0.5f, RenderProgress(framesDone = 45, totalFrames = 90).fraction)
        assertEquals(0f, RenderProgress(framesDone = 0, totalFrames = 90).fraction)
        assertEquals(1f, RenderProgress(framesDone = 90, totalFrames = 90).fraction)
        assertEquals(0f, RenderProgress(framesDone = 0, totalFrames = 0).fraction)
    }

    @Test
    fun etaMs_measuredOrNull() {
        assertNull(RenderProgress(10, 100, framesPerSecond = 0.0).etaMs)
        assertNull(RenderProgress(100, 100, framesPerSecond = 20.0).etaMs)
        // 90 frames left at 30 fps → 3000 ms.
        assertEquals(3_000L, RenderProgress(10, 100, framesPerSecond = 30.0).etaMs)
    }

    @Test
    fun bus_lifecycle() {
        ExportProgressBus.reset()
        assertFalse(ExportProgressBus.isActive())
        ExportProgressBus.post(RenderState.Preparing)
        assertTrue(ExportProgressBus.isActive())
        ExportProgressBus.post(RenderState.Rendering, RenderProgress(5, 100))
        assertTrue(ExportProgressBus.isActive())
        val done = RenderState.Completed(
            uri = Uri.parse("content://media/1"),
            fileName = "Timeline_2026-09-30_1200.mp4",
            durationMs = 90_000L,
            width = 1280,
            height = 720,
            fps = 30,
            sizeBytes = 12_000_000L,
        )
        ExportProgressBus.post(done)
        assertFalse(ExportProgressBus.isActive())
        assertEquals(done, ExportProgressBus.lastCompleted)
        ExportProgressBus.post(RenderState.Failed("nope"))
        assertFalse(ExportProgressBus.isActive())
        ExportProgressBus.reset()
        assertNull(ExportProgressBus.lastCompleted)
    }
}

// ---------------------------------------------------------------------------
// ExportCameraPlanner — deterministic camera on a known route.
// Lahore → Islamabad → Murree → Skardu.
// ---------------------------------------------------------------------------

class ExportCameraPlannerTest {

    private val route = listOf(
        ExportCameraPlanner.CamPoint(31.5204, 74.3587), // Lahore
        ExportCameraPlanner.CamPoint(33.6844, 73.0479), // Islamabad
        ExportCameraPlanner.CamPoint(33.9062, 73.3903), // Murree
        ExportCameraPlanner.CamPoint(35.2971, 75.6333), // Skardu
    )

    @Test
    fun overview_isDeterministicAndCoversRoute() {
        val a = ExportCameraPlanner(route, CompositionCameraMode.FIXED_OVERVIEW, 1920, 1080)
        val b = ExportCameraPlanner(route, CompositionCameraMode.FIXED_OVERVIEW, 1920, 1080)
        assertEquals(a.overviewCamera, b.overviewCamera)
        val cam = a.overviewCamera
        assertTrue("zoom must be positive, was ${cam.zoom}", cam.zoom > 0.0)
        assertTrue("center lat ${cam.lat} outside route span", cam.lat in 31.0..36.0)
        assertTrue("center lng ${cam.lng} outside route span", cam.lng in 72.5..76.0)
    }

    @Test
    fun overview_portraitIsWiderThanLandscapeForWideRoute() {
        // A long east-west route: landscape width fits a closer zoom than
        // the narrow portrait frame.
        val wide = listOf(
            ExportCameraPlanner.CamPoint(35.0, 60.0),
            ExportCameraPlanner.CamPoint(35.0, 90.0),
        )
        val landscape = ExportCameraPlanner(wide, CompositionCameraMode.FIXED_OVERVIEW, 1920, 1080)
        val portrait = ExportCameraPlanner(wide, CompositionCameraMode.FIXED_OVERVIEW, 1080, 1920)
        assertTrue(
            "portrait ${portrait.overviewCamera.zoom} vs landscape ${landscape.overviewCamera.zoom}",
            portrait.overviewCamera.zoom < landscape.overviewCamera.zoom,
        )
    }

    @Test
    fun follow_replayIsIdentical() {
        fun drive(): List<ExportCamera> {
            val planner = ExportCameraPlanner(route, CompositionCameraMode.FOLLOW_JOURNEY, 1280, 720)
            // Walk the marker along the route in frame order.
            return (0 until 120).map { i ->
                val t = i / 119.0
                val lat = 31.5204 + t * (35.2971 - 31.5204)
                val lng = 74.3587 + t * (75.6333 - 74.3587)
                planner.cameraFor(i / 30.0, lat, lng)
            }
        }
        assertEquals(drive(), drive())
    }

    @Test
    fun follow_tuningMatchesPreviewContract() {
        val follow = ExportCameraPlanner(route, CompositionCameraMode.FOLLOW_JOURNEY, 1280, 720)
        val smart = ExportCameraPlanner(route, CompositionCameraMode.SMART_FOLLOW, 1280, 720)
        assertEquals(0.25f, follow.viewportFraction)
        assertEquals(0.4, follow.throttleSec, 1e-9)
        assertEquals(0.40f, smart.viewportFraction)
        assertEquals(0.8, smart.throttleSec, 1e-9)
    }

    @Test
    fun follow_startsAtOverview() {
        val planner = ExportCameraPlanner(route, CompositionCameraMode.FOLLOW_JOURNEY, 1280, 720)
        val first = planner.cameraFor(0.0, route[0].lat, route[0].lng)
        assertEquals(planner.overviewCamera, first)
    }

    @Test
    fun sameFrameTimestamp_sameAnimationState_acrossReplays() {
        // The export contract: frame index → video time → animation state is
        // a pure function, so two replays must agree on marker positions.
        val resolved = route.mapIndexed { i, p ->
            ResolvedPoint(
                TrackPoint(p.lat, p.lng, i * 3_600_000L),
                unresolvedLocation(p.lat, p.lng),
            )
        }
        val fps = 30
        val totalMs = 12_000L
        val frames = RenderClock.totalFrames(totalMs, fps)
        fun markerPositions(): List<Pair<Double, Double>> {
            val timeline = AnimationTimeline.build(
                resolved,
                AnimationConfig(targetTotalMs = totalMs),
            )
            assertNotNull(timeline)
            val tl = timeline!!
            return (0 until frames).map { i ->
                val t = RenderClock.videoTimeMs(i, fps)
                val frame = tl.stateAt(t)
                Pair(frame.lat, frame.lng)
            }
        }
        val run1 = markerPositions()
        val run2 = markerPositions()
        assertEquals(run1.size, run2.size)
        assertEquals(run1, run2)
        // And the route is actually traversed: first ≠ last marker.
        assertNotEquals(run1.first(), run1.last())
    }
}
