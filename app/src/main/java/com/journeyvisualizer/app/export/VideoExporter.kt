package com.journeyvisualizer.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.composition.ExportSpec
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.data.geo.CityStop
import com.journeyvisualizer.app.map.BasemapStyles
import com.journeyvisualizer.app.map.CameraMode
import com.journeyvisualizer.app.map.FrameRenderer
import com.journeyvisualizer.app.map.JourneyEngine
import com.journeyvisualizer.app.map.TileCache
import com.journeyvisualizer.app.map.TileKey
import com.journeyvisualizer.app.map.TimeWarp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportConfig(
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val fps: Int = 30,
    val cameraMode: CameraMode,
    val timeWarp: TimeWarp = TimeWarp.LINEAR,
    val title: String,
    val cities: List<CityStop> = emptyList(),
)

/**
 * Renders a [Journey] to MP4 with the hardware H.264 encoder.
 *
 * Frames are drawn with the same [FrameRenderer] as the interactive preview,
 * uploaded as a single texture per frame through [EglCore], and muxed with
 * [MediaMuxer]. Runs on a background dispatcher; the caller (a foreground
 * service) owns progress UI and cancellation.
 */
class VideoExporter(private val context: Context) {

    suspend fun export(
        journey: Journey,
        config: ExportConfig,
        outputUri: Uri,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) = withContext(Dispatchers.Default) {
        require(journey.points.size >= 2) { "Journey needs at least 2 points" }
        require(config.durationSec in 10..300) { "Duration must be 10..300 seconds" }

        val engine = JourneyEngine(journey, config.timeWarp)
        val tileCache = TileCache(context.applicationContext)

        // Warm the tile cache for the whole journey so rendering rarely stalls.
        val prefetch = engine.warmTileKeys(config.width, config.height)
        prefetch.forEachIndexed { i, key ->
            if (isCancelled()) throw CancellationException()
            tileCache.getTile(key.z, key.x, key.y)
            if (i % 8 == 0) onProgress(0.05f * i / prefetch.size.coerceAtLeast(1))
        }

        val bitrate = (config.width.toLong() * config.height * config.fps * 0.15)
            .toInt().coerceIn(2_000_000, 16_000_000)

        val format = MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        var egl: EglCore? = null
        var muxer: MediaMuxer? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            egl = EglCore(inputSurface, config.width, config.height)
            encoder.start()

            val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
                ?: throw IllegalStateException("Could not open output")
            pfd.use {
                muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                runEncodeLoop(
                    encoder, muxer!!, egl!!, engine, tileCache, config,
                    onProgress, isCancelled
                )
            }
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            try {
                egl?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
        onProgress(1f)
    }

    // ------------------------------------------------------------------
    // Phase 7 — deterministic export from Phase 5 timeline + Phase 6 spec.
    // ------------------------------------------------------------------

    /**
     * Renders [request] to MP4.
     *
     * The pipeline is: spec + timeline → deterministic frame clock
     * ([RenderClock]) → [AnimationTimeline.stateAt] (the SAME animation the
     * preview uses — never a second algorithm) → [ExportCameraPlanner] →
     * [CompositionFrameRenderer] → [EglCore] → hardware AVC → [MediaMuxer].
     *
     * States are reported via [onState]; [onProgress] carries real
     * frame-derived progress. Cancellation throws [CancellationException];
     * classified failures throw [RenderException]. Never blocks the main
     * thread (runs on [Dispatchers.Default]); one reusable frame bitmap is
     * held at a time, never the whole sequence.
     */
    suspend fun exportPhase7(
        request: Phase7RenderRequest,
        outputUri: Uri,
        onState: (RenderState) -> Unit,
        onProgress: (RenderProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) = withContext(Dispatchers.Default) {
        val spec = request.spec
        val timeline = request.timeline
        validatePhase7(spec, timeline)

        val width = spec.exportWidth
        val height = spec.exportHeight
        val fps = spec.fps
        val totalFrames = RenderClock.totalFrames(spec.totalVideoMs, fps)

        onState(RenderState.Preparing)

        // 1. Encoder preflight — fail fast on unsupported configurations.
        val requestedBitrate = BitratePolicy.bitrateFor(width, height, fps)
        val probe = EncoderProbe.check(width, height, fps, requestedBitrate)
        if (!probe.ok) throw RenderException(probe.error ?: RenderError.EncoderUnavailable())
        val bitrate = probe.bitrate

        // 2. Simulate the deterministic camera path and prefetch every tile
        //    the render will need. Finite timeouts; failures are counted.
        val styleSpec = BasemapStyles.specFor(spec.mapStyle)
        val maxZoom = styleSpec.base.maxZoom
        val camPoints = timeline.points.map { ExportCameraPlanner.CamPoint(it.lat, it.lng) }
        val planner = ExportCameraPlanner(camPoints, spec.cameraMode, width, height, maxZoom)
        val allKeys = LinkedHashSet<TileKey>()
        for (i in 0 until totalFrames) {
            if (isCancelled()) throw CancellationException()
            val cam = cameraForFrame(planner, timeline, spec, i, fps)
            allKeys += planner.visibleTileKeys(cam, styleSpec.base.minZoom, maxZoom)
        }
        val tileCache = StyleTileCache(
            context.applicationContext,
            listOfNotNull(styleSpec.base, styleSpec.overlay),
        )
        val prefetch = tileCache.prefetch(allKeys) { _, _ ->
            if (isCancelled()) throw CancellationException()
        }

        // 3. Missing-tile policy: never silently render a broken map.
        //    Evaluated against base-layer coverage, the same definition the
        //    renderer uses to count a missing tile.
        if (prefetch.failedBaseKeys.isNotEmpty()) {
            enforceTileCoverage(planner, timeline, spec, totalFrames, fps, prefetch.failedBaseKeys)
        }

        // 4. Encode.
        onState(RenderState.Rendering)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MIME)
        } catch (e: Exception) {
            throw RenderException(RenderError.EncoderUnavailable(), e)
        }
        var egl: EglCore? = null
        var muxer: MediaMuxer? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            egl = EglCore(inputSurface, width, height)
            encoder.start()

            val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
                ?: throw RenderException(RenderError.StorageFailure)
            pfd.use {
                muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                runPhase7EncodeLoop(
                    encoder, muxer!!, egl!!, request, totalFrames, tileCache,
                    onState, onProgress, isCancelled,
                )
            }
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            try {
                egl?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Deterministic camera for frame [frameIndex]: video-clock time →
     * animation state → follow/plan. The planner is driven in ascending
     * frame order from 0, so this is a pure function of the frame index.
     */
    private fun cameraForFrame(
        planner: ExportCameraPlanner,
        timeline: AnimationTimeline,
        spec: ExportSpec,
        frameIndex: Int,
        fps: Int,
    ): ExportCamera {
        val videoTimeMs = RenderClock.videoTimeMs(frameIndex, fps)
        val animMs = (videoTimeMs - spec.introMs).coerceIn(0, timeline.totalDurationMs)
        val state = timeline.stateAt(animMs)
        return planner.cameraFor(videoTimeMs / 1000.0, state.lat, state.lng)
    }

    /**
     * Fails the render when tile coverage is too poor to trust: any frame
     * missing more than half its map, or more than 15% missing on average.
     */
    private fun enforceTileCoverage(
        planner: ExportCameraPlanner,
        timeline: AnimationTimeline,
        spec: ExportSpec,
        totalFrames: Int,
        fps: Int,
        failedBaseKeys: Set<TileKey>,
    ) {
        val styleSpec = BasemapStyles.specFor(spec.mapStyle)
        val checkPlanner = ExportCameraPlanner(
            timeline.points.map { ExportCameraPlanner.CamPoint(it.lat, it.lng) },
            spec.cameraMode, spec.exportWidth, spec.exportHeight, styleSpec.base.maxZoom,
        )
        var worst = 0.0
        var sum = 0.0
        for (i in 0 until totalFrames) {
            val cam = cameraForFrame(checkPlanner, timeline, spec, i, fps)
            val keys = checkPlanner.visibleTileKeys(cam, styleSpec.base.minZoom, styleSpec.base.maxZoom)
            if (keys.isEmpty()) continue
            val miss = keys.count { it in failedBaseKeys }.toDouble() / keys.size
            if (miss > worst) worst = miss
            sum += miss
        }
        if (worst > MAX_MISSING_FRACTION_PER_FRAME || sum / totalFrames > MAX_MISSING_FRACTION_MEAN) {
            throw RenderException(RenderError.TilesUnavailable(failedBaseKeys.size))
        }
    }

    private fun runPhase7EncodeLoop(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        egl: EglCore,
        request: Phase7RenderRequest,
        totalFrames: Int,
        tileCache: StyleTileCache,
        onState: (RenderState) -> Unit,
        onProgress: (RenderProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val spec = request.spec
        val timeline = request.timeline
        val width = spec.exportWidth
        val height = spec.exportHeight
        val fps = spec.fps
        val journeyEndMs = spec.totalVideoMs - spec.outroMs

        val styleSpec = BasemapStyles.specFor(spec.mapStyle)
        val planner = ExportCameraPlanner(
            timeline.points.map { ExportCameraPlanner.CamPoint(it.lat, it.lng) },
            spec.cameraMode, width, height, styleSpec.base.maxZoom,
        )
        val renderer = CompositionFrameRenderer(
            context.applicationContext, spec, timeline.points, planner,
        )
        val drainer = EncoderDrainer(encoder, muxer)

        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBitmap)
        var lastReportNs = System.nanoTime()
        var framesSinceReport = 0
        var fpsEstimate = 0.0
        try {
            for (i in 0 until totalFrames) {
                if (isCancelled()) throw CancellationException()
                val videoTimeMs = RenderClock.videoTimeMs(i, fps)
                val region = RenderClock.regionFor(videoTimeMs, spec.introMs, journeyEndMs)
                val animMs = (videoTimeMs - spec.introMs).coerceIn(0, timeline.totalDurationMs)
                val anim = timeline.stateAt(animMs)
                val cam = planner.cameraFor(videoTimeMs / 1000.0, anim.lat, anim.lng)
                renderer.drawFrame(
                    canvas, width, height,
                    CompositionFrameRenderer.FrameRequest(videoTimeMs, region, anim, cam),
                ) { key -> tileCache.getCachedLayers(key) }
                // Exact presentation timestamp — no wall-clock drift.
                egl.drawFrame(frameBitmap, RenderClock.presentationTimeNs(i, fps))
                drainer.drain(endOfStream = false)

                framesSinceReport++
                val nowNs = System.nanoTime()
                if (nowNs - lastReportNs >= 500_000_000L || i == totalFrames - 1) {
                    val elapsedSec = (nowNs - lastReportNs) / 1e9
                    if (elapsedSec > 0) fpsEstimate = framesSinceReport / elapsedSec
                    onProgress(RenderProgress(i + 1, totalFrames, fpsEstimate))
                    lastReportNs = nowNs
                    framesSinceReport = 0
                }
            }
            onState(RenderState.Finalizing)
            encoder.signalEndOfInputStream()
            drainer.drain(endOfStream = true)
            if (!drainer.muxerStarted) {
                throw RenderException(RenderError.EncoderUnavailable("Encoder produced no output"))
            }
            // Finalize the container before reporting success.
            muxer.stop()
        } finally {
            frameBitmap.recycle()
            tileCache.clearMemory()
        }
        onProgress(RenderProgress(totalFrames, totalFrames, fpsEstimate))
    }

    private fun validatePhase7(spec: ExportSpec, timeline: AnimationTimeline) {
        if (timeline.eventCount == 0 || timeline.points.isEmpty()) {
            throw RenderException(RenderError.EmptyTimeline)
        }
        if (spec.exportWidth <= 0 || spec.exportHeight <= 0 ||
            spec.exportWidth % 2 != 0 || spec.exportHeight % 2 != 0
        ) {
            throw RenderException(RenderError.InvalidComposition)
        }
        if (spec.fps !in SUPPORTED_FPS) throw RenderException(RenderError.InvalidComposition)
        if (spec.totalVideoMs <= 0) throw RenderException(RenderError.InvalidComposition)
        if (spec.totalVideoMs - spec.introMs - spec.outroMs <= 0) {
            throw RenderException(RenderError.InvalidComposition)
        }
    }

    /**
     * Encoder output drain shared by the Phase 7 loop: writes samples to
     * the muxer, starts the muxer on format change, and reports end of
     * stream. (The legacy Phase 1 loop keeps its own local drain
     * untouched.)
     */
    private class EncoderDrainer(
        private val encoder: MediaCodec,
        private val muxer: MediaMuxer,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        var muxerStarted = false
            private set

        fun drain(endOfStream: Boolean) {
            // Bound the end-of-stream wait: a broken driver that never emits
            // EOS must fail the render, not hang the foreground service.
            var eosWaits = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(
                    bufferInfo, if (endOfStream) 10_000 else 0,
                )
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++eosWaits > MAX_EOS_WAITS) {
                            throw RenderException(
                                RenderError.EncoderUnavailable("Encoder did not finish"),
                            )
                        }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed twice" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val data = encoder.getOutputBuffer(idx)
                        if (data != null && bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    companion object {
        private const val MIME = "video/avc"
        private const val ACCENT = 0xFF38BDF8.toInt()
        private val SUPPORTED_FPS = setOf(24, 30, 60)
        private const val MAX_MISSING_FRACTION_PER_FRAME = 0.5
        private const val MAX_MISSING_FRACTION_MEAN = 0.15
        /** ~9 s of 10 ms waits before a missing EOS fails the render. */
        private const val MAX_EOS_WAITS = 900
    }

    // ------------------------------------------------------------------
    // Legacy Phase 1 loop — kept untouched for the old screens.
    // ------------------------------------------------------------------

    private fun runEncodeLoop(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        egl: EglCore,
        engine: JourneyEngine,
        tileCache: TileCache,
        config: ExportConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var trackIndex = -1

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(
                    bufferInfo, if (endOfStream) 10_000 else 0
                )
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed twice" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val data = encoder.getOutputBuffer(idx)
                        if (data != null && bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return
                        }
                    }
                }
            }
        }

        val frameBitmap =
            Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBitmap)
        val totalFrames = config.durationSec * config.fps
        val durationD = config.durationSec.toDouble()

        for (i in 0 until totalFrames) {
            if (isCancelled()) throw CancellationException()
            val videoTime = i.toDouble() / config.fps
            FrameRenderer.drawFrame(
                canvas, config.width, config.height,
                engine, videoTime, durationD,
                config.cameraMode,
                { key -> tileCache.getTile(key.z, key.x, key.y) },
                config.title, ACCENT,
                config.cities,
            )
            egl.drawFrame(frameBitmap, i * 1_000_000_000L / config.fps)
            drain(false)
            if (i % config.fps == 0) {
                onProgress(0.05f + 0.95f * i / totalFrames)
            }
        }

        encoder.signalEndOfInputStream()
        drain(true)
        if (!muxerStarted) throw IllegalStateException("Encoder produced no output")
    }
}
