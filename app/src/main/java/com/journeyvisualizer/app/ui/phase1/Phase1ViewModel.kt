package com.journeyvisualizer.app.ui.phase1

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyvisualizer.app.animation.AnimationConfig
import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.CameraFollowMode
import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.composition.CompositionCodec
import com.journeyvisualizer.app.composition.CompositionValidator
import com.journeyvisualizer.app.composition.VideoComposition
import com.journeyvisualizer.app.composition.toExportSpec
import com.journeyvisualizer.app.composition.VideoPreviewController
import com.journeyvisualizer.app.export.Phase7RenderRequest
import com.journeyvisualizer.app.data.FailureReason
import com.journeyvisualizer.app.data.SettingsRepository
import com.journeyvisualizer.app.data.TimelineParser
import com.journeyvisualizer.app.data.geo.GeoNamesRepository
import com.journeyvisualizer.app.data.geo.LocationResolver
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.settings.AppSettings
import com.journeyvisualizer.app.settings.defaultVideoComposition
import com.journeyvisualizer.app.ui.phase1.components.MapPin
import com.journeyvisualizer.app.ui.phase1.mock.MockData
import com.journeyvisualizer.app.ui.util.formatDate
import com.journeyvisualizer.app.ui.util.formatFileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Phase 1 UI state + Phase 2 real Timeline import.
 *
 * This holds the new UX selections (map style, video settings) and the real
 * import pipeline state ([ImportState]). It does NOT replace
 * [com.journeyvisualizer.app.data.JourneyViewModel], which keeps driving the
 * pre-existing export pipeline underneath.
 */
enum class MapStyle { STANDARD, SATELLITE, HYBRID, TERRAIN }

class Phase1ViewModel : ViewModel() {

    // -- Map style -----------------------------------------------------------
    var mapStyle by mutableStateOf(MapStyle.SATELLITE)
        private set

    fun selectMapStyle(style: MapStyle) { mapStyle = style }

    // -- Real map selection (Phase 4) ------------------------------------------
    /**
     * Stable timeline index of the map-selected point, or null when nothing
     * is selected. Shared by the event list and the map so both stay in sync.
     */
    var selectedMapIndex by mutableStateOf<Int?>(null)
        private set

    fun selectMapPoint(index: Int?) { selectedMapIndex = index }

    /**
     * Load the persisted basemap style (DataStore). Keeps the in-memory
     * default when nothing was saved or the value is unknown.
     */
    fun loadPersistedMapStyle(context: Context) {
        viewModelScope.launch {
            try {
                val name = com.journeyvisualizer.app.data.SettingsRepository(context.applicationContext)
                    .mapStyle
                    .firstOrNull()
                if (name != null) {
                    mapStyle = runCatching { MapStyle.valueOf(name) }.getOrDefault(mapStyle)
                }
            } catch (_: Exception) {
                // Non-fatal: keep the default style.
            }
        }
    }

    /** Persist the current basemap style for the next session. */
    fun persistMapStyle(context: Context) {
        viewModelScope.launch {
            try {
                com.journeyvisualizer.app.data.SettingsRepository(context.applicationContext)
                    .setMapStyle(mapStyle.name)
            } catch (_: Exception) {
                // Non-fatal.
            }
        }
    }

    // -- Real Timeline import (Phase 2) ---------------------------------------
    //
    // The file is read ONCE and parsed ONCE on a background thread; the
    // resulting [ImportState.Ready] (with its normalized Journey) is kept here
    // so Upload → Processing → Summary → Preview → Customize never reparse.

    var importState by mutableStateOf<ImportState>(ImportState.Idle)
        private set

    private var importJob: Job? = null

    /** Files larger than this are refused with a clear message, not an OOM. */
    private val maxImportBytes = 256L * 1024 * 1024

    /** True once a real file (or the bundled sample) has been parsed. */
    val hasRealImport: Boolean get() = importState is ImportState.Ready

    /**
     * Starts importing the user-picked document [uri].
     *
     * Uses the Storage Access Framework with no storage permissions:
     * GetContent grants a one-shot read grant for exactly the picked file.
     */
    fun startImport(context: Context, uri: Uri) {
        cancelImport()
        val app = context.applicationContext
        importJob = viewModelScope.launch {
            val cr = app.contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "timeline.json"
            val sizeLabel = cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && i >= 0) {
                    c.getLong(i).takeIf { it > 0 }?.let(::formatFileSize)
                } else null
            }
            runImport(app, name, sizeLabel) { cr.openInputStream(uri) }
        }
    }

    /** Parses the small bundled sample through the exact same real pipeline. */
    fun startSampleImport(context: Context) {
        cancelImport()
        val app = context.applicationContext
        importJob = viewModelScope.launch {
            runImport(app, "sample_timeline.json", null) {
                app.assets.open("sample_timeline.json")
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    fun clearImport() {
        cancelImport()
        rebuildJob?.cancel()
        animationEngine?.detach()
        animationEngine = null
        videoPreviewController?.detach()
        videoPreviewController = null
        lastResolvedPoints = emptyList()
        importState = ImportState.Idle
    }

    /**
     * Phase 5 animation engine for the current import, or null when there is
     * no import (or nothing animatable). Built on a background thread during
     * import; the ticker is attached to [viewModelScope] so playback
     * survives rotation, and detached in [onCleared].
     */
    var animationEngine by mutableStateOf<AnimationEngine?>(null)
        private set

    /** Phase 5 route visualization mode (FULL vs PROGRESSIVE). */
    var routeDrawMode by mutableStateOf(RouteDrawMode.FULL)

    // -- Phase 6 video composition -----------------------------------------

    /**
     * Video composition state: aspect ratio, resolution, overlays, etc.
     * Survives rotation; persisted to DataStore. Changing duration-related
     * fields rebuilds the animation timeline from the kept resolved points
     * (never reparses JSON, never re-runs GeoNames).
     */
    var videoComposition by mutableStateOf(VideoComposition.DEFAULT)
        private set

    /**
     * Phase 9: persisted user settings (theme, video defaults, playback
     * defaults). Loaded from DataStore; [AppSettings.DEFAULT] until loaded.
     * The values are sanitized on load, so they are always safe to apply.
     */
    var appSettings by mutableStateOf(AppSettings.DEFAULT)
        private set

    /** Loads the Phase 9 user settings; keeps defaults on failure. */
    fun loadPersistedSettings(context: Context) {
        viewModelScope.launch {
            try {
                val loaded = SettingsRepository(context.applicationContext)
                    .appSettings.firstOrNull()
                if (loaded != null) appSettings = loaded
            } catch (_: Exception) {
                // Keep defaults.
            }
        }
    }

    /**
     * Composition-layer preview player wrapping the shared animation
     * engine (intro + journey + outro video clock). Null until an import
     * produces an animation engine.
     */
    var videoPreviewController by mutableStateOf<VideoPreviewController?>(null)
        private set

    /** Resolved points kept in memory so duration changes rebuild cheaply. */
    private var lastResolvedPoints: List<ResolvedPoint> = emptyList()
    private var lastImportFileName: String = ""
    private var rebuildJob: Job? = null
    private var appContextRef: Context? = null

    /** Timeline reference for the Phase 7 export spec (file name, not data). */
    val timelineRef: String get() = lastImportFileName

    /**
     * Builds the Phase 7 render request from the live Phase 5 timeline and
     * the current Phase 6 composition — the same spec the preview shows.
     * Returns null when there is no timeline or the composition does not
     * validate; the UI surfaces the validator's messages instead.
     */
    fun buildPhase7Request(): Phase7RenderRequest? {
        val engine = animationEngine ?: return null
        val timeline = engine.timeline
        if (timeline.eventCount == 0) return null
        val issues = CompositionValidator.validate(
            videoComposition,
            hasTimeline = true,
            animationTotalMs = timeline.totalDurationMs,
        )
        if (issues.isNotEmpty()) return null
        val ref = timelineRef.ifBlank { "timeline" }
        return Phase7RenderRequest(
            timeline = timeline,
            spec = videoComposition.toExportSpec(timeline, ref),
            timelineRef = ref,
        )
    }

    fun updateComposition(transform: (VideoComposition) -> VideoComposition) {
        val old = videoComposition
        val new = transform(old)
        if (new == old) return
        videoComposition = new
        persistComposition()
        val durationAffecting = old.durationMode != new.durationMode ||
            old.customDurationSec != new.customDurationSec ||
            old.introEnabled != new.introEnabled ||
            old.introDurationSec != new.introDurationSec ||
            old.outroEnabled != new.outroEnabled ||
            old.outroDurationSec != new.outroDurationSec
        if (durationAffecting) rebuildAnimationForComposition()
        else syncPreviewTimings()
    }

    /** One-tap preset; only touches composition, never timeline data. */
    fun applyCompositionPreset(preset: VideoComposition) {
        updateComposition { preset }
    }

    /**
     * Restores default video settings from the user's Phase 9 preferences
     * (preset + individual defaults). Timeline, GeoNames results, and the
     * imported file are untouched — only the composition is replaced.
     */
    fun resetComposition() {
        updateComposition { defaultVideoComposition(appSettings) }
    }

    /**
     * Rebuilds the animation timeline with the composition's duration
     * target. Events are never dropped — only time compression changes —
     * and the original timestamps stay correct.
     */
    private fun rebuildAnimationForComposition() {
        if (lastResolvedPoints.isEmpty()) {
            syncPreviewTimings()
            return
        }
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch(Dispatchers.Default) {
            val target = videoComposition.animationTargetMs().coerceIn(10_000L, 600_000L)
            val timeline = AnimationTimeline.build(
                lastResolvedPoints,
                // Phase 9: the user's default playback speed becomes the
                // engine's initial speed (sanitized on load).
                AnimationConfig(
                    targetTotalMs = target,
                    defaultSpeed = appSettings.defaultPlaybackSpeed,
                ),
            )
            withContext(Dispatchers.Main) {
                val newEngine = timeline?.let { AnimationEngine(it) }
                animationEngine?.detach()
                animationEngine = newEngine
                newEngine?.attach(viewModelScope)
                // Phase 9: the user's follow-camera default becomes the new
                // engine's initial camera mode (the preview's AnimationDriver
                // reads engine.followMode, so this is the live default).
                newEngine?.setFollowMode(
                    if (appSettings.followCameraByDefault) CameraFollowMode.FOLLOW
                    else CameraFollowMode.FREE,
                )
                val ctl = videoPreviewController
                if (newEngine != null) {
                    if (ctl == null) {
                        videoPreviewController = VideoPreviewController(newEngine, viewModelScope)
                            .also { syncPreviewTimings(it) }
                    } else {
                        ctl.bind(newEngine)
                        syncPreviewTimings(ctl)
                    }
                } else {
                    ctl?.detach()
                    videoPreviewController = null
                }
            }
        }
    }

    private fun syncPreviewTimings() {
        videoPreviewController?.let { syncPreviewTimings(it) }
    }

    private fun syncPreviewTimings(ctl: VideoPreviewController) {
        ctl.introMs = videoComposition.introMs()
        ctl.outroMs = videoComposition.outroMs()
    }

    /** Load the persisted composition (DataStore). Keeps defaults on failure. */
    fun loadPersistedComposition(context: Context) {
        appContextRef = context.applicationContext
        viewModelScope.launch {
            try {
                val encoded = SettingsRepository(context.applicationContext)
                    .videoComposition.firstOrNull()
                if (!encoded.isNullOrBlank()) {
                    videoComposition = CompositionCodec.decode(encoded)
                }
            } catch (_: Exception) {
                // Keep defaults.
            }
        }
    }

    private fun persistComposition() {
        val ctx = appContextRef ?: return
        val encoded = CompositionCodec.encode(videoComposition)
        viewModelScope.launch {
            try {
                SettingsRepository(ctx).saveVideoComposition(encoded)
            } catch (_: Exception) {
                // Persistence is best-effort; in-memory state is authoritative.
            }
        }
    }

    override fun onCleared() {
        rebuildJob?.cancel()
        videoPreviewController?.detach()
        animationEngine?.detach()
        super.onCleared()
    }

    private fun setStage(fileName: String, stage: ImportStage, progress: Float) {
        importState = ImportState.Parsing(fileName, stage, progress.coerceIn(0f, 1f))
    }

    private suspend fun runImport(
        appContext: Context,
        fileName: String,
        sizeLabel: String?,
        open: () -> InputStream?,
    ) {
        val fail = { reason: FailureReason, detail: String ->
            // A failed import must not leave a stale animation behind.
            animationEngine?.detach()
            animationEngine = null
            videoPreviewController?.detach()
            videoPreviewController = null
            lastResolvedPoints = emptyList()
            importState = ImportState.Failed(fileName, reason, detail)
        }
        importState = ImportState.Reading(fileName, sizeLabel)
        try {
            // Read the file exactly once; everything downstream works from memory.
            val bytes = withContext(Dispatchers.IO) { open()?.use { it.readBytes() } }
            if (bytes == null) {
                fail(FailureReason.READ_ERROR, "Unable to read this file. Please choose another file.")
                return
            }
            if (bytes.isEmpty()) {
                fail(FailureReason.EMPTY_FILE, "Timeline file is empty.")
                return
            }
            if (bytes.size > maxImportBytes) {
                fail(FailureReason.TOO_LARGE, "This file is too large to process on this device.")
                return
            }

            // Detect the Timeline format from the first non-whitespace byte.
            setStage(fileName, ImportStage.DETECTING, 0.15f)
            val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
                ?.toInt()?.toChar()
            if (first != '[' && first != '{') {
                fail(FailureReason.NOT_JSON, "This file could not be recognized as a Timeline export.")
                return
            }

            // Parse on a background thread; the UI stays responsive.
            val result = withContext(Dispatchers.IO) {
                TimelineParser.parse(bytes.inputStream(), fileName) { frac ->
                    setStage(fileName, ImportStage.PARSING, 0.2f + 0.55f * frac)
                }
            }
            val journey = result.journey
            if (journey == null || result.pointCount == 0) {
                val reason = result.failureReason ?: FailureReason.NO_POINTS
                // Name what the file actually contained: a "no points" failure
                // with 0 records means an empty export; with N records it means
                // a format whose coordinates we did not understand.
                val detail = buildString {
                    append(reasonCopy(reason))
                    if (result.recordCount > 0 && result.formatName.isNotEmpty()) {
                        append(" (Found ${result.recordCount} records in '${result.formatName}' format.")
                        if (result.firstRecordKeys.isNotEmpty()) {
                            append(" First record keys: ${result.firstRecordKeys.joinToString(", ")}.")
                        }
                        append(")")
                    }
                }
                fail(reason, detail)
                return
            }

            // Resolve locations against the bundled GeoNames data (Phase 3).
            // The dataset loads lazily once per session — never at startup —
            // and every lookup stays on background threads. If the asset is
            // missing, the import still succeeds with honest "Location N"
            // pins instead of failing.
            val resolver = try {
                LocationResolver(GeoNamesRepository.get(appContext))
            } catch (_: Exception) {
                null
            }
            val resolvedPoints: List<ResolvedPoint> = if (resolver != null) {
                resolver.resolveAll(journey.points) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 1f
                    setStage(fileName, ImportStage.RESOLVING, 0.76f + 0.18f * frac)
                }
            } else {
                journey.points.map { p -> ResolvedPoint(p, unresolvedLocation(p.lat, p.lng)) }
            }

            // Finalize: real metadata + preview pins derived from real coordinates.
            setStage(fileName, ImportStage.FINALIZING, 0.95f)
            val pins = resolvedPoints.toPreviewPins()
            val firstDay = formatDate(journey.startMs)
            val lastDay = formatDate(journey.endMs)

            // Phase 5+6: build the deterministic animation timeline on the
            // background thread (pure CPU work), honoring the composition's
            // duration target, then swap the engine in. Resolved points are
            // kept so duration changes rebuild cheaply — never reparse the
            // JSON, never re-run GeoNames.
            lastResolvedPoints = resolvedPoints
            lastImportFileName = fileName
            val animTarget = videoComposition.animationTargetMs().coerceIn(10_000L, 600_000L)
            val newEngine = withContext(Dispatchers.Default) {
                AnimationTimeline.build(
                    resolvedPoints,
                    // Phase 9: default playback speed from user settings.
                    AnimationConfig(
                        targetTotalMs = animTarget,
                        defaultSpeed = appSettings.defaultPlaybackSpeed,
                    ),
                )?.let { AnimationEngine(it) }
            }
            animationEngine?.detach()
            animationEngine = newEngine
            newEngine?.attach(viewModelScope)
            // Phase 9: the user's follow-camera default becomes the new
            // engine's initial camera mode.
            newEngine?.setFollowMode(
                if (appSettings.followCameraByDefault) CameraFollowMode.FOLLOW
                else CameraFollowMode.FREE,
            )
            val oldCtl = videoPreviewController
            if (newEngine != null) {
                if (oldCtl == null) {
                    videoPreviewController = VideoPreviewController(newEngine, viewModelScope)
                        .also { syncPreviewTimings(it) }
                } else {
                    oldCtl.bind(newEngine)
                    syncPreviewTimings(oldCtl)
                }
            } else {
                oldCtl?.detach()
                videoPreviewController = null
            }

            importState = ImportState.Ready(
                ImportSummary(
                    fileName = fileName,
                    sizeLabel = formatFileSize(bytes.size.toLong()),
                    recordCount = result.recordCount,
                    isSegmentFormat = result.formatName == "semantic",
                    validPoints = result.pointCount,
                    invalidPoints = (result.rawPointCount - result.pointCount).coerceAtLeast(0),
                    dateRangeLabel = if (firstDay == lastDay) firstDay else "$firstDay — $lastDay",
                    journey = journey,
                    resolvedPoints = resolvedPoints,
                    pins = pins,
                    warnings = result.warnings,
                )
            )
        } catch (e: CancellationException) {
            importState = ImportState.Idle
            throw e
        } catch (_: Exception) {
            // Never leak raw JSON or coordinates into the message.
            fail(FailureReason.READ_ERROR, "Unable to read this file. Please choose another file.")
        }
    }

    private fun reasonCopy(reason: FailureReason): String = when (reason) {
        FailureReason.EMPTY_FILE -> "Timeline file is empty."
        FailureReason.READ_ERROR -> "Unable to read this file. Please choose another file."
        FailureReason.NOT_JSON -> "This file contains invalid JSON."
        FailureReason.NOT_TIMELINE -> "This Timeline format is not supported."
        FailureReason.NO_POINTS -> "No geographic locations were found in this timeline."
        FailureReason.TOO_LARGE -> "This file is too large to process on this device."
    }

    /**
     * Pins for preview screens: real parsed data when available, otherwise
     * the clearly-labeled sample pins used for UI previews.
     */
    fun previewPins(): List<MapPin> =
        (importState as? ImportState.Ready)?.summary?.pins?.takeIf { it.isNotEmpty() }
            ?: MockData.events
}
