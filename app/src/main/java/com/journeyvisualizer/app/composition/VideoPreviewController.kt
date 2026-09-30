package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Video preview player (Phase 6).
 *
 * This is NOT a second animation system. It owns the *video* clock —
 * intro + journey animation + outro — and drives the real Phase 5
 * [AnimationEngine] for the journey portion:
 *
 * - Scrubbing maps deterministically: videoTime → engine.seekTo(videoTime − intro).
 * - During real-time playback the engine is the clock: the controller snaps
 *   the video position to intro + engine.position every tick.
 * - Intro/outro regions are simple title cards advanced by this controller.
 *
 * Same timeline + same animation settings + same camera/route logic as the
 * Phase 5 preview, so what you see is what Phase 7 will export.
 */
enum class VideoPreviewState {
    IDLE,
    INTRO,
    PLAYING,
    PAUSED,
    OUTRO,
    COMPLETED,
}

class VideoPreviewController(
    private var engine: AnimationEngine,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** False in unit tests, where [advanceForTest] drives the clock. */
    private val enableTicker: Boolean = true,
) {
    private val _videoPositionMs = MutableStateFlow(0L)
    val videoPositionMs: StateFlow<Long> = _videoPositionMs

    private val _previewState = MutableStateFlow(VideoPreviewState.IDLE)
    val previewState: StateFlow<VideoPreviewState> = _previewState

    /** Intro length in ms; set from the composition when it changes. */
    var introMs: Long = 0L

    /** Outro length in ms; set from the composition when it changes. */
    var outroMs: Long = 0L

    val animationTotalMs: Long get() = engine.getTotalDuration()
    val totalVideoMs: Long get() = introMs + animationTotalMs + outroMs

    val engineRef: AnimationEngine get() = engine

    private var ticker: Job? = null
    private var lastTickMs: Long = 0L

    /** Rebind after the engine is rebuilt (new import / new duration). */
    fun bind(newEngine: AnimationEngine) {
        engine = newEngine
        reset()
    }

    fun play() {
        val total = totalVideoMs
        if (total <= 0) return
        if (_videoPositionMs.value >= total) _videoPositionMs.value = 0L
        _previewState.value = regionState(_videoPositionMs.value)
        lastTickMs = clockMs()
        startTicker()
    }

    fun pause() {
        stopTicker()
        engine.pause()
        if (_previewState.value != VideoPreviewState.COMPLETED) {
            _previewState.value = VideoPreviewState.PAUSED
        }
    }

    fun seekTo(videoMs: Long) {
        val total = totalVideoMs
        val pos = videoMs.coerceIn(0, total)
        _videoPositionMs.value = pos
        engine.seekTo((pos - introMs).coerceIn(0, animationTotalMs))
        _previewState.value = when {
            pos >= total && total > 0 -> VideoPreviewState.COMPLETED
            ticker?.isActive == true -> regionState(pos)
            else -> VideoPreviewState.PAUSED
        }
        if (pos >= total && total > 0) stopTicker()
    }

    fun reset() {
        stopTicker()
        engine.stop()
        _videoPositionMs.value = 0L
        _previewState.value = VideoPreviewState.IDLE
    }

    /** Screen gone: stop ticking and park the engine. Never leaks. */
    fun detach() {
        stopTicker()
        engine.pause()
    }

    /** True while the intro title card should cover the frame. */
    fun isInIntro(): Boolean = introMs > 0 && _videoPositionMs.value < introMs

    /** True while the outro card should cover the frame. */
    fun isInOutro(): Boolean {
        val journeyEnd = introMs + animationTotalMs
        return outroMs > 0 && _videoPositionMs.value >= journeyEnd
    }

    /** Deterministic clock-free stepping for tests. No-op unless playing. */
    fun advanceForTest(dtMs: Long) {
        when (_previewState.value) {
            VideoPreviewState.INTRO,
            VideoPreviewState.PLAYING,
            VideoPreviewState.OUTRO -> tick(dtMs)
            VideoPreviewState.IDLE,
            VideoPreviewState.PAUSED,
            VideoPreviewState.COMPLETED -> Unit
        }
    }

    // -- Ticker ------------------------------------------------------------

    private fun startTicker() {
        if (!enableTicker) return
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = clockMs()
                val dt = (now - lastTickMs).coerceIn(0, MAX_DT_MS)
                lastTickMs = now
                tick(dt)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun tick(dtMs: Long) {
        val animMs = animationTotalMs
        val total = introMs + animMs + outroMs
        if (total <= 0) {
            _previewState.value = VideoPreviewState.COMPLETED
            stopTicker()
            return
        }
        var pos = _videoPositionMs.value
        when {
            pos < introMs -> {
                // Intro card region: this controller advances the clock.
                pos += dtMs
                if (pos >= introMs) {
                    pos = introMs
                    engine.seekTo(0)
                    engine.play()
                }
                _previewState.value = VideoPreviewState.INTRO
            }
            pos < introMs + animMs -> {
                // Journey region: the Phase 5 engine is the clock.
                if (engine.getCurrentState() == PlaybackState.COMPLETED &&
                    engine.getCurrentPosition() >= animMs
                ) {
                    // The engine finished on its own: the journey is over.
                    pos = introMs + animMs
                } else {
                    if (engine.getCurrentState() != PlaybackState.PLAYING) {
                        engine.seekTo((pos - introMs).coerceIn(0, animMs))
                        engine.play()
                    }
                    pos = introMs + engine.getCurrentPosition()
                }
                _previewState.value = VideoPreviewState.PLAYING
            }
            else -> {
                // Outro card region: park the engine at the journey end.
                if (engine.getCurrentState() == PlaybackState.PLAYING) engine.pause()
                if (engine.getCurrentPosition() < animMs) engine.seekTo(animMs)
                pos += dtMs
                if (pos >= total) {
                    pos = total
                    _previewState.value = VideoPreviewState.COMPLETED
                    stopTicker()
                } else {
                    _previewState.value = VideoPreviewState.OUTRO
                }
            }
        }
        _videoPositionMs.value = pos.coerceIn(0, total)
    }

    private fun regionState(pos: Long): VideoPreviewState = when {
        introMs > 0 && pos < introMs -> VideoPreviewState.INTRO
        outroMs > 0 && pos >= introMs + animationTotalMs -> VideoPreviewState.OUTRO
        else -> VideoPreviewState.PLAYING
    }

    companion object {
        private const val TICK_MS = 33L
        private const val MAX_DT_MS = 250L
    }
}
