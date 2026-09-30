package com.journeyvisualizer.app.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process render state bus (Phase 7).
 *
 * The foreground [ExportService] owns the render and posts every state
 * transition and progress update here; Compose screens collect
 * [snapshot] to show live progress. Because export runs in a foreground
 * service, navigation or screen rotation never restarts or duplicates the
 * work — reopening the screen just re-collects the current snapshot.
 *
 * State is intentionally NOT persisted across process death: the render
 * inputs (the full [AnimationTimeline]) live in the ViewModel and are
 * not re-serializable, so after a process kill the bus resets to Idle and
 * the user simply starts the export again.
 */
object ExportProgressBus {

    data class Snapshot(
        val state: RenderState,
        val progress: RenderProgress?,
    )

    private val _snapshot = MutableStateFlow(Snapshot(RenderState.Idle, null))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Last successfully completed render, for the result screen. */
    @Volatile
    var lastCompleted: RenderState.Completed? = null
        private set

    fun post(state: RenderState, progress: RenderProgress? = null) {
        if (state is RenderState.Completed) lastCompleted = state
        _snapshot.value = Snapshot(state, progress)
    }

    fun reset() {
        lastCompleted = null
        _snapshot.value = Snapshot(RenderState.Idle, null)
    }

    /** True while a render is actively running. */
    fun isActive(): Boolean = when (_snapshot.value.state) {
        is RenderState.Preparing,
        is RenderState.Rendering,
        is RenderState.Finalizing,
        -> true
        else -> false
    }
}
