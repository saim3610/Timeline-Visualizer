package com.journeyvisualizer.app.animation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The animation controller (Phase 5): the single source of truth for playback.
 *
 * Split from the timeline model on purpose:
 * - [AnimationTimeline] is pure data + deterministic frame math (reusable by
 *   a future video renderer with no wall clock).
 * - This engine owns playback state, the monotonic clock, speed, seeking,
 *   and event navigation.
 *
 * The engine never touches views or the map directly; it publishes
 * [PlaybackState] and [AnimationFrameState] flows that the UI layer applies
 * to the [com.journeyvisualizer.app.map.InteractiveMapController].
 * Must be driven from the main thread (it mirrors UI state).
 */
class AnimationEngine(
    val timeline: AnimationTimeline,
    /**
     * Monotonic clock in ms (defaults to nanoTime). Injected so tests can
     * drive time deterministically.
     */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    /** The one playback state; the UI must not keep a competing one. */
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _frame = MutableStateFlow(timeline.stateAt(0L))
    /** Latest computed frame: marker, route progress, timestamp, event. */
    val frame: StateFlow<AnimationFrameState> = _frame.asStateFlow()

    private val _speed = MutableStateFlow(timeline.config.defaultSpeed)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val _followMode = MutableStateFlow(CameraFollowMode.FOLLOW)
    val followMode: StateFlow<CameraFollowMode> = _followMode.asStateFlow()

    private var positionMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var tickerJob: Job? = null

    // ------------------------------------------------------------------
    // Playback controls
    // ------------------------------------------------------------------

    /**
     * Start or resume playback from the current position. Restarting from
     * COMPLETED replays the journey from the beginning.
     */
    fun play() {
        when (_state.value) {
            PlaybackState.PLAYING -> return
            PlaybackState.COMPLETED, PlaybackState.STOPPED -> seekTo(0L)
            PlaybackState.ERROR -> return
            else -> Unit
        }
        if (timeline.totalDurationMs <= 0L) {
            // Nothing to animate (single point): jump straight to done.
            positionMs = 0L
            publishFrame()
            _state.value = PlaybackState.COMPLETED
            return
        }
        lastTickMs = clockMs()
        _state.value = PlaybackState.PLAYING
    }

    /** Freeze everything: marker, route, camera and timestamp hold still. */
    fun pause() {
        if (_state.value != PlaybackState.PLAYING) return
        advanceClock()
        _state.value = PlaybackState.PAUSED
    }

    /**
     * Stop and reset to the beginning. Imported timeline data is untouched —
     * only playback position and map overlays reset.
     */
    fun stop() {
        positionMs = 0L
        publishFrame()
        _state.value = PlaybackState.STOPPED
    }

    /** Back to the start and playing. */
    fun restart() {
        seekTo(0L)
        play()
    }

    /**
     * Jump to an animation-clock position. Efficient: binary search, no
     * replay from the beginning. Keeps the current play/pause state so
     * scrubbing never restarts playback by itself.
     */
    fun seekTo(positionMs: Long) {
        val state = _state.value
        if (state == PlaybackState.ERROR || state == PlaybackState.PREPARING) return
        this.positionMs = positionMs.coerceIn(0L, timeline.totalDurationMs)
        lastTickMs = clockMs()
        publishFrame()
        // Landing exactly at the end (or a zero-length timeline) completes.
        if (this.positionMs >= timeline.totalDurationMs) {
            _state.value = PlaybackState.COMPLETED
        } else if (state == PlaybackState.COMPLETED || state == PlaybackState.STOPPED) {
            _state.value = PlaybackState.PAUSED
        }
    }

    /** Jump to the event with the given stable timeline id. */
    fun seekToEvent(eventId: Int) {
        val pos = timeline.points.indexOfFirst { it.id == eventId }
        if (pos < 0) return
        // Position at the start of the segment leaving this event (or the
        // end when it is the final event).
        val segIdx = timeline.segments.indexOfFirst { it.from.id == eventId }
        seekTo(if (segIdx >= 0) timeline.segments[segIdx].startAnimMs else timeline.totalDurationMs)
    }

    /** Move to the next event; no-op at the journey end. */
    fun nextEvent() {
        val current = _frame.value.eventId
        val pos = timeline.points.indexOfFirst { it.id == current }
        if (pos < 0 || pos >= timeline.points.size - 1) {
            seekTo(timeline.totalDurationMs)
            return
        }
        seekToEvent(timeline.points[pos + 1].id)
    }

    /** Move to the previous event; no-op at the journey start. */
    fun previousEvent() {
        val current = _frame.value.eventId
        val pos = timeline.points.indexOfFirst { it.id == current }
        if (pos <= 0) {
            seekTo(0L)
            return
        }
        seekToEvent(timeline.points[pos - 1].id)
    }

    /** Speed multiplies animation time; points are never skipped. */
    fun setSpeed(speed: Double) {
        require(speed.isFinite() && speed > 0) { "speed must be a positive finite number" }
        if (_state.value == PlaybackState.PLAYING) advanceClock()
        _speed.value = speed
    }

    fun setFollowMode(mode: CameraFollowMode) {
        _followMode.value = mode
    }

    // ------------------------------------------------------------------
    // Getters (spec §33)
    // ------------------------------------------------------------------

    fun getCurrentState(): PlaybackState = _state.value
    fun getCurrentPosition(): Long = positionMs
    fun getCurrentTimestamp(): Long = _frame.value.displayedTimestampMs
    fun getCurrentEvent(): AnimationPoint = _frame.value.currentPoint
    fun getTotalDuration(): Long = timeline.totalDurationMs

    // ------------------------------------------------------------------
    // Ticker: advances the clock while attached; detach to stop.
    // ------------------------------------------------------------------

    /**
     * Start the frame loop in [scope]. Call from the owning ViewModel;
     * [detach] in `onCleared`. Safe to call twice.
     */
    fun attach(scope: CoroutineScope) {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                advanceClock()
                delay(FRAME_DELAY_MS)
            }
        }
    }

    fun detach() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Advance the animation clock by the elapsed wall time (scaled by speed).
     * Called by the ticker; tests drive it via [advanceForTest] instead.
     */
    fun advanceClock() {
        if (_state.value != PlaybackState.PLAYING) return
        val now = clockMs()
        val elapsed = (now - lastTickMs).coerceAtLeast(0L)
        lastTickMs = now
        if (elapsed == 0L) return
        positionMs += (elapsed * _speed.value).toLong()
        if (positionMs >= timeline.totalDurationMs) {
            positionMs = timeline.totalDurationMs
            publishFrame()
            _state.value = PlaybackState.COMPLETED
        } else {
            publishFrame()
        }
    }

    /** Deterministic time injection for tests (bypasses the wall clock). */
    fun advanceForTest(elapsedMs: Long) {
        if (_state.value != PlaybackState.PLAYING) return
        positionMs += (elapsedMs.coerceAtLeast(0L) * _speed.value).toLong()
        if (positionMs >= timeline.totalDurationMs) {
            positionMs = timeline.totalDurationMs
            publishFrame()
            _state.value = PlaybackState.COMPLETED
        } else {
            publishFrame()
        }
    }

    private fun publishFrame() {
        _frame.value = timeline.stateAt(positionMs)
    }

    companion object {
        private const val FRAME_DELAY_MS = 33L // ~30 fps ticker
    }
}
