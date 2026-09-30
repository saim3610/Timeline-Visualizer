package com.journeyvisualizer.app.ui.phase1.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.AnimationFrameState
import com.journeyvisualizer.app.animation.CameraFollowMode
import com.journeyvisualizer.app.animation.PlaybackState
import com.journeyvisualizer.app.composition.CompositionPresets
import com.journeyvisualizer.app.composition.CompositionValidator
import com.journeyvisualizer.app.composition.OverlayPosition
import com.journeyvisualizer.app.composition.OverlayTextAlign
import com.journeyvisualizer.app.composition.OverlayTextSize
import com.journeyvisualizer.app.composition.OverlayTextWeight
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.VideoComposition
import com.journeyvisualizer.app.composition.VideoDurationMode
import com.journeyvisualizer.app.composition.VideoFps
import com.journeyvisualizer.app.composition.VideoPreviewController
import com.journeyvisualizer.app.composition.VideoPreviewState
import com.journeyvisualizer.app.composition.VideoResolutionPreset
import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.export.BitratePolicy
import com.journeyvisualizer.app.export.EncoderProbe
import com.journeyvisualizer.app.export.ExportProgressBus
import com.journeyvisualizer.app.export.ExportService
import com.journeyvisualizer.app.export.RenderState
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.InteractiveMapController
import com.journeyvisualizer.app.map.OsmMapController
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.map.toMapPoint
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.components.ChipOptions
import com.journeyvisualizer.app.ui.phase1.components.CompositionNote
import com.journeyvisualizer.app.ui.phase1.components.CompositionSection
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.PrimaryButton
import com.journeyvisualizer.app.ui.phase1.components.SliderOption
import com.journeyvisualizer.app.ui.phase1.components.TextOption
import com.journeyvisualizer.app.ui.phase1.components.ToggleOption
import com.journeyvisualizer.app.ui.phase1.map.TimelineMap
import com.journeyvisualizer.app.ui.util.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Phase 6 — professional video preview & composition.
 *
 * The preview consumes the REAL Phase 5 [AnimationEngine] (same timeline,
 * same animation settings, same camera/route logic) through the
 * composition-layer [VideoPreviewController], which adds the video clock:
 * intro + journey animation + outro. No second animation system, no
 * temporary MP4s — the composition renders directly on the live map.
 *
 * The [VideoComposition] lives in the ViewModel: it survives rotation,
 * persists to DataStore, and never touches timeline/GeoNames data.
 */
@Composable
fun VideoPreviewScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onExportComplete: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ux.loadPersistedComposition(context)
        ux.loadPersistedSettings(context)
    }

    val composition = ux.videoComposition
    val engine = ux.animationEngine
    val ctl = ux.videoPreviewController

    // Own controller for the preview map — never recreated for setting
    // changes; composition applies through controller methods instead.
    val mapController = remember { OsmMapController(context.applicationContext) }

    // Park the preview at the start whenever a fresh controller appears
    // (screen entry, new import, duration rebuild).
    LaunchedEffect(ctl) { ctl?.reset() }

    // Phase 9: autoplay the preview on entry when the user enabled it in
    // Settings → Playback. One shot per controller instance.
    var autoplayFired by remember(ctl) { mutableStateOf(false) }
    LaunchedEffect(ctl, ux.appSettings.autoplayPreview) {
        if (ctl != null && ux.appSettings.autoplayPreview && !autoplayFired) {
            autoplayFired = true
            ctl.play()
        }
    }

    // Lifecycle: pause preview playback when backgrounded; never leak.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, ctl) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) ctl?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(mapController) {
        onDispose { mapController.detach() }
    }

    if (engine != null && ctl != null) {
        VideoPreviewDriver(
            engine = engine,
            controller = mapController,
            composition = composition,
        )
    }

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    // Re-attach to an in-flight render when returning to this screen —
    // the foreground service owns the work, never the screen.
    var showExportProgress by remember { mutableStateOf(ExportProgressBus.isActive()) }

    val issues = remember(composition, engine) {
        CompositionValidator.validate(
            composition,
            hasTimeline = engine != null,
            animationTotalMs = engine?.getTotalDuration() ?: 0L,
        )
    }

    Scaffold(topBar = {
        JVTopBar(
            title = stringResource(R.string.vpreview_title),
            onBack = {
                ctl?.pause()
                onBack()
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            if (engine == null || ctl == null) {
                Text(
                    text = stringResource(R.string.vpreview_no_timeline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            } else {
                // -- Aspect-ratio preview frame ------------------------------
                // Only one TimelineMap is composed at a time: the fullscreen
                // dialog takes over the (single) controller while open, so the
                // two MapViews never fight over it.
                if (!fullscreen) {
                    VideoFrameContent(
                        controller = mapController,
                        composition = composition,
                        ctl = ctl,
                        engine = engine,
                        onFullscreen = { fullscreen = true },
                        onCycleMapStyle = {
                            val styles = BasemapStyle.values()
                            val next = styles[(composition.mapStyle.ordinal + 1) % styles.size]
                            ux.updateComposition { it.copy(mapStyle = next) }
                        },
                        onRecenter = {
                            engine.setFollowMode(CameraFollowMode.FOLLOW)
                            val f = engine.frame.value
                            mapController.recenterOn(f.lat, f.lng)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // -- Transport (video clock) ----------------------------------
                PreviewTransport(ctl = ctl)

                Spacer(Modifier.height(4.dp))

                // -- Composition settings -------------------------------------
                CompositionSettings(
                    composition = composition,
                    engine = engine,
                    onUpdate = { ux.updateComposition(it) },
                    onPreset = { ux.applyCompositionPreset(it) },
                    onReset = { ux.resetComposition() },
                )

                Spacer(Modifier.height(12.dp))

                // -- Validation ------------------------------------------------
                if (issues.isNotEmpty()) {
                    CompositionSection(title = stringResource(R.string.vpreview_issues)) {
                        issues.forEach { issue ->
                            Text(
                                text = "• ${issue.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                PrimaryButton(
                    text = stringResource(R.string.vpreview_continue),
                    onClick = { showExportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = issues.isEmpty(),
                )
                CompositionNote(
                    text = stringResource(R.string.vpreview_continue_note),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (fullscreen && engine != null && ctl != null) {
        FullscreenPreview(
            controller = mapController,
            composition = composition,
            ctl = ctl,
            engine = engine,
            onClose = { fullscreen = false },
            onCycleMapStyle = {
                val styles = BasemapStyle.values()
                val next = styles[(composition.mapStyle.ordinal + 1) % styles.size]
                ux.updateComposition { it.copy(mapStyle = next) }
            },
            onRecenter = {
                engine.setFollowMode(CameraFollowMode.FOLLOW)
                val f = engine.frame.value
                mapController.recenterOn(f.lat, f.lng)
            },
        )
    }

    if (showExportDialog && engine != null) {
        ExportDialog(
            composition = composition,
            engine = engine,
            timelineRef = ux.timelineRef,
            onApplyFallback = { preset, fps ->
                ux.updateComposition { it.copy(resolutionPreset = preset, fps = fps) }
            },
            onStartExport = {
                val req = ux.buildPhase7Request()
                if (req == null) {
                    // Should not happen: the continue button is gated on
                    // validation, but never start an invalid render.
                    return@ExportDialog
                }
                showExportDialog = false
                // false = a render is already active; re-attach to it
                // instead of starting a duplicate.
                ExportService.enqueuePhase7(context, req)
                showExportProgress = true
            },
            onDismiss = { showExportDialog = false },
        )
    }

    if (showExportProgress) {
        ExportProgressDialog(
            onDone = { showExportProgress = false },
            onCompleted = {
                showExportProgress = false
                onExportComplete()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Driver: the engine's frames → map, honoring the composition.
// ---------------------------------------------------------------------------

/**
 * Applies the Phase 5 engine's frames to the map under composition control.
 * Camera modes reuse the Phase 5 follow logic (no second camera system):
 * follow = tight dead-zone, smart follow = wide dead-zone + calm throttle,
 * fixed overview = fit bounds once, no tracking.
 */
@Composable
private fun VideoPreviewDriver(
    engine: AnimationEngine,
    controller: InteractiveMapController,
    composition: VideoComposition,
) {
    val frame by engine.frame.collectAsState()
    val state by engine.state.collectAsState()
    val followMode by engine.followMode.collectAsState()

    DisposableEffect(controller, engine) {
        controller.setOnUserInteractionListener {
            engine.setFollowMode(CameraFollowMode.FREE)
        }
        onDispose { controller.setOnUserInteractionListener(null) }
    }

    LaunchedEffect(composition.cameraMode) {
        when (composition.cameraMode) {
            CompositionCameraMode.FOLLOW_JOURNEY -> {
                controller.setFollowTuning(0.25f, 400L)
                engine.setFollowMode(CameraFollowMode.FOLLOW)
            }
            CompositionCameraMode.SMART_FOLLOW -> {
                controller.setFollowTuning(0.40f, 800L)
                engine.setFollowMode(CameraFollowMode.FOLLOW)
            }
            CompositionCameraMode.FIXED_OVERVIEW -> {
                engine.setFollowMode(CameraFollowMode.FREE)
                controller.fitTimelineBounds(animated = true)
            }
        }
    }

    LaunchedEffect(
        composition.routeDrawMode,
        composition.routeVisible,
        composition.routeWidthScale,
        composition.routeOpacity,
    ) {
        controller.setRouteDrawMode(composition.routeDrawMode)
        controller.setRouteAppearance(
            composition.routeVisible,
            composition.routeWidthScale,
            composition.routeOpacity,
        )
    }
    LaunchedEffect(composition.markerStyle) {
        controller.setAnimatedMarkerStyle(composition.markerStyle)
    }
    LaunchedEffect(composition.startEndMarkers) {
        controller.setStartEndMarkersVisible(composition.startEndMarkers)
    }

    LaunchedEffect(frame) {
        if (composition.markerStyle == VideoMarkerStyle.HIDDEN) {
            controller.hideAnimatedMarker()
        } else {
            controller.setAnimatedMarkerPosition(frame.lat, frame.lng)
        }
        controller.setAnimatedRouteProgress(frame.traveledPointCount, frame.lat, frame.lng)
        if (followMode == CameraFollowMode.FOLLOW &&
            (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED)
        ) {
            controller.followAnimatedMarker(frame.lat, frame.lng)
        }
    }
}

// ---------------------------------------------------------------------------
// Preview frame: aspect-ratio box with the live map + overlays.
// ---------------------------------------------------------------------------

@Composable
private fun VideoFrameContent(
    controller: InteractiveMapController,
    composition: VideoComposition,
    ctl: VideoPreviewController,
    engine: AnimationEngine,
    onFullscreen: () -> Unit,
    onCycleMapStyle: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapPoints = remember(engine) {
        engine.timeline.points.map { it.toMapPoint() }
    }
    val frame by engine.frame.collectAsState()
    val followMode by engine.followMode.collectAsState()

    Box(
        modifier = modifier
            .aspectRatio(composition.aspectRatio.ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        if (mapPoints.isNotEmpty()) {
            TimelineMap(
                controller = controller,
                points = mapPoints,
                style = composition.mapStyle,
                selectedIndex = null,
                onMarkerClick = {},
                onStyleClick = onCycleMapStyle,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Composition overlays (location / date / title / progress).
        VideoOverlays(
            composition = composition,
            frame = frame,
            videoPositionMs = ctl.videoPositionMs.collectAsState().value,
            totalVideoMs = ctl.totalVideoMs,
        )

        // Intro / outro title cards.
        if (ctl.isInIntro()) {
            IntroCard(composition = composition)
        } else if (ctl.isInOutro()) {
            OutroCard(composition = composition, frame = frame)
        }

        // Fullscreen + recenter controls (kept clear of overlay text).
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (followMode == CameraFollowMode.FREE &&
                composition.cameraMode != CompositionCameraMode.FIXED_OVERVIEW
            ) {
                TextButton(onClick = onRecenter) {
                    Text(
                        text = stringResource(R.string.vpreview_recenter),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
            IconButton(onClick = onFullscreen) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.vpreview_fullscreen),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Safe margins for overlay text; 9:16 keeps clear of OS/social UI zones. */
private fun safePaddingFor(aspect: VideoAspectRatio): PaddingValues =
    if (aspect == VideoAspectRatio.NINE_SIXTEEN) {
        PaddingValues(top = 88.dp, bottom = 160.dp, start = 20.dp, end = 20.dp)
    } else {
        PaddingValues(top = 16.dp, bottom = 16.dp, start = 20.dp, end = 20.dp)
    }

@Composable
private fun BoxScope.VideoOverlays(
    composition: VideoComposition,
    frame: AnimationFrameState,
    videoPositionMs: Long,
    totalVideoMs: Long,
) {
    val hasText = composition.title.isNotBlank() ||
        composition.showLocation || composition.showDateTime
    if (hasText) {
        val alignment = when (composition.overlayPosition) {
            OverlayPosition.TOP -> Alignment.TopCenter
            OverlayPosition.TOP_LEFT -> Alignment.TopStart
            OverlayPosition.TOP_RIGHT -> Alignment.TopEnd
            OverlayPosition.BOTTOM -> Alignment.BottomCenter
            OverlayPosition.BOTTOM_LEFT -> Alignment.BottomStart
            OverlayPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
        }
        val horizontal = when (composition.textAlign) {
            OverlayTextAlign.START -> Alignment.Start
            OverlayTextAlign.CENTER -> Alignment.CenterHorizontally
            OverlayTextAlign.END -> Alignment.End
        }
        val textAlign = when (composition.textAlign) {
            OverlayTextAlign.START -> TextAlign.Start
            OverlayTextAlign.CENTER -> TextAlign.Center
            OverlayTextAlign.END -> TextAlign.End
        }
        val weight = when (composition.textWeight) {
            OverlayTextWeight.REGULAR -> FontWeight.Normal
            OverlayTextWeight.BOLD -> FontWeight.Bold
        }
        val scale = composition.textSize.scale
        Column(
            modifier = Modifier
                .align(alignment)
                .padding(safePaddingFor(composition.aspectRatio))
                .padding(8.dp),
            horizontalAlignment = horizontal,
        ) {
            if (composition.title.isNotBlank()) {
                OverlayText(
                    text = composition.title,
                    fontSize = 20.sp * scale,
                    weight = weight,
                    align = textAlign,
                )
            }
            if (composition.subtitle.isNotBlank()) {
                OverlayText(
                    text = composition.subtitle,
                    fontSize = 14.sp * scale,
                    weight = FontWeight.Normal,
                    align = textAlign,
                )
            }
            if (composition.showLocation) {
                val point = frame.currentPoint
                OverlayText(
                    text = point.displayName,
                    fontSize = 18.sp * scale,
                    weight = weight,
                    align = textAlign,
                )
                val sub = point.country
                    ?: "%.4f°, %.4f°".format(Locale.US, point.lat, point.lng)
                OverlayText(
                    text = sub,
                    fontSize = 13.sp * scale,
                    weight = FontWeight.Normal,
                    align = textAlign,
                )
            }
            if (composition.showDateTime) {
                OverlayText(
                    text = formatDateTime(frame.displayedTimestampMs),
                    fontSize = 13.sp * scale,
                    weight = FontWeight.Normal,
                    align = textAlign,
                )
            }
        }
    }

    if (composition.showProgress && totalVideoMs > 0) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
        ) {
            Text(
                text = "${formatMs(videoPositionMs)} / ${formatMs(totalVideoMs)}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { (videoPositionMs.toFloat() / totalVideoMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF38BDF8),
                trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun OverlayText(
    text: String,
    fontSize: TextUnit,
    weight: FontWeight,
    align: TextAlign,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = fontSize,
        fontWeight = weight,
        textAlign = align,
        style = MaterialTheme.typography.bodyLarge.copy(
            shadow = androidx.compose.ui.graphics.Shadow(
                color = Color.Black.copy(alpha = 0.6f),
                blurRadius = 6f,
            ),
        ),
    )
}

@Composable
private fun BoxScope.IntroCard(composition: VideoComposition) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = composition.title.ifBlank { stringResource(R.string.vpreview_intro_default) },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (composition.subtitle.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = composition.subtitle,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.OutroCard(
    composition: VideoComposition,
    frame: AnimationFrameState,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.vpreview_outro_title),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (composition.outroShowFinalLocation) {
                Spacer(Modifier.height(8.dp))
                val point = frame.currentPoint
                Text(
                    text = point.displayName,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDateTime(frame.displayedTimestampMs),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transport: play/pause + video-clock scrubber.
// ---------------------------------------------------------------------------

@Composable
private fun PreviewTransport(ctl: VideoPreviewController) {
    val pos by ctl.videoPositionMs.collectAsState()
    val state by ctl.previewState.collectAsState()
    val total = ctl.totalVideoMs
    val playing = state == VideoPreviewState.PLAYING ||
        state == VideoPreviewState.INTRO ||
        state == VideoPreviewState.OUTRO

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(
            onClick = { if (playing) ctl.pause() else ctl.play() },
            enabled = total > 0,
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.anim_pause else R.string.anim_play
                ),
            )
        }
        Text(
            text = formatMs(pos),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = pos.toFloat(),
            onValueChange = { ctl.seekTo(it.toLong()) },
            valueRange = 0f..total.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(1f),
            enabled = total > 0,
        )
        Text(
            text = formatMs(total),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
        )
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

// ---------------------------------------------------------------------------
// Fullscreen preview dialog.
// ---------------------------------------------------------------------------

@Composable
private fun FullscreenPreview(
    controller: InteractiveMapController,
    composition: VideoComposition,
    ctl: VideoPreviewController,
    engine: AnimationEngine,
    onClose: () -> Unit,
    onCycleMapStyle: () -> Unit,
    onRecenter: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // The composition frame keeps its aspect ratio (fit-centered);
            // the map is never stretched.
            VideoFrameContent(
                controller = controller,
                composition = composition,
                ctl = ctl,
                engine = engine,
                onFullscreen = onClose,
                onCycleMapStyle = onCycleMapStyle,
                onRecenter = onRecenter,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.vpreview_close_fullscreen),
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Composition settings UI.
// ---------------------------------------------------------------------------

@Composable
private fun CompositionSettings(
    composition: VideoComposition,
    engine: AnimationEngine,
    onUpdate: ((VideoComposition) -> VideoComposition) -> Unit,
    onPreset: (VideoComposition) -> Unit,
    onReset: () -> Unit,
) {
    CompositionSection(title = stringResource(R.string.vpreview_presets)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompositionPresets.all().forEach { (name, preset) ->
                OutlinedButton(onClick = { onPreset(preset) }) { Text(name) }
            }
        }
        OutlinedButton(onClick = onReset) {
            Text(stringResource(R.string.vpreview_reset))
        }
        CompositionNote(text = stringResource(R.string.vpreview_reset_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_aspect)) {
        ChipOptions(
            options = listOf("16:9", "9:16", "1:1"),
            selectedIndex = composition.aspectRatio.ordinal,
            onSelect = { index -> onUpdate { it.copy(aspectRatio = VideoAspectRatio.values()[index]) } },
        )
        CompositionNote(text = stringResource(R.string.vpreview_aspect_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_resolution)) {
        val (w, h) = composition.exportSize()
        ChipOptions(
            options = listOf("720p", "1080p", "1440p", "4K"),
            selectedIndex = composition.resolutionPreset.ordinal,
            onSelect = { index -> onUpdate { it.copy(resolutionPreset = VideoResolutionPreset.values()[index]) } },
        )
        Text(
            text = stringResource(R.string.vpreview_export_size, w, h),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        CompositionNote(text = stringResource(R.string.vpreview_resolution_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_fps)) {
        ChipOptions(
            options = listOf("24", "30", "60"),
            selectedIndex = composition.fps.ordinal,
            onSelect = { index -> onUpdate { it.copy(fps = VideoFps.values()[index]) } },
        )
        CompositionNote(text = stringResource(R.string.vpreview_fps_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_duration)) {
        val animMs = engine.getTotalDuration()
        val totalMs = composition.introMs() + animMs + composition.outroMs()
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_dur_auto),
                stringResource(R.string.vpreview_dur_short),
                stringResource(R.string.vpreview_dur_medium),
                stringResource(R.string.vpreview_dur_long),
                stringResource(R.string.vpreview_dur_custom),
            ),
            selectedIndex = composition.durationMode.ordinal,
            onSelect = { index -> onUpdate { it.copy(durationMode = VideoDurationMode.values()[index]) } },
        )
        if (composition.durationMode == VideoDurationMode.CUSTOM) {
            SliderOption(
                title = stringResource(R.string.vpreview_dur_custom_label),
                value = composition.customDurationSec.toFloat(),
                onValueChange = { onUpdate { c -> c.copy(customDurationSec = it.toInt()) } },
                valueRange = 10f..600f,
                steps = 0,
                valueLabel = stringResource(R.string.vpreview_seconds, composition.customDurationSec),
            )
        }
        CompositionNote(
            text = stringResource(
                R.string.vpreview_duration_breakdown,
                formatMs(totalMs),
                composition.introMs() / 1000,
                formatMs(animMs),
                composition.outroMs() / 1000,
            ),
        )
        CompositionNote(text = stringResource(R.string.vpreview_duration_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_mapstyle)) {
        ChipOptions(
            options = listOf(
                stringResource(R.string.style_standard),
                stringResource(R.string.style_satellite),
                stringResource(R.string.style_hybrid),
                stringResource(R.string.style_terrain),
            ),
            selectedIndex = composition.mapStyle.ordinal,
            onSelect = { index -> onUpdate { it.copy(mapStyle = BasemapStyle.values()[index]) } },
        )
    }

    CompositionSection(title = stringResource(R.string.vpreview_camera)) {
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_cam_follow),
                stringResource(R.string.vpreview_cam_smart),
                stringResource(R.string.vpreview_cam_fixed),
            ),
            selectedIndex = composition.cameraMode.ordinal,
            onSelect = { index -> onUpdate { it.copy(cameraMode = CompositionCameraMode.values()[index]) } },
        )
        CompositionNote(text = stringResource(R.string.vpreview_camera_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_route)) {
        ToggleOption(
            title = stringResource(R.string.vpreview_route_show),
            checked = composition.routeVisible,
            onChecked = { checked -> onUpdate { it.copy(routeVisible = checked) } },
        )
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_route_progressive),
                stringResource(R.string.vpreview_route_full),
            ),
            selectedIndex = if (composition.routeDrawMode == RouteDrawMode.PROGRESSIVE) 0 else 1,
            onSelect = {
                onUpdate { c ->
                    c.copy(routeDrawMode = if (it == 0) RouteDrawMode.PROGRESSIVE else RouteDrawMode.FULL)
                }
            },
        )
        SliderOption(
            title = stringResource(R.string.vpreview_route_width),
            value = composition.routeWidthScale,
            onValueChange = { onUpdate { c -> c.copy(routeWidthScale = it) } },
            valueRange = 0.5f..2f,
            steps = 0,
            valueLabel = "%.1f×".format(Locale.US, composition.routeWidthScale),
        )
        SliderOption(
            title = stringResource(R.string.vpreview_route_opacity),
            value = composition.routeOpacity,
            onValueChange = { onUpdate { c -> c.copy(routeOpacity = it) } },
            valueRange = 0.2f..1f,
            steps = 0,
            valueLabel = "%d%%".format(Locale.US, (composition.routeOpacity * 100).toInt()),
        )
    }

    CompositionSection(title = stringResource(R.string.vpreview_marker)) {
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_marker_standard),
                stringResource(R.string.vpreview_marker_minimal),
                stringResource(R.string.vpreview_marker_highlighted),
                stringResource(R.string.vpreview_marker_hidden),
            ),
            selectedIndex = composition.markerStyle.ordinal,
            onSelect = { index -> onUpdate { it.copy(markerStyle = VideoMarkerStyle.values()[index]) } },
        )
        ToggleOption(
            title = stringResource(R.string.vpreview_startend),
            subtitle = stringResource(R.string.vpreview_startend_sub),
            checked = composition.startEndMarkers,
            onChecked = { checked -> onUpdate { it.copy(startEndMarkers = checked) } },
        )
    }

    CompositionSection(title = stringResource(R.string.vpreview_overlay)) {
        ToggleOption(
            title = stringResource(R.string.vpreview_overlay_location),
            checked = composition.showLocation,
            onChecked = { checked -> onUpdate { it.copy(showLocation = checked) } },
        )
        ToggleOption(
            title = stringResource(R.string.vpreview_overlay_datetime),
            checked = composition.showDateTime,
            onChecked = { checked -> onUpdate { it.copy(showDateTime = checked) } },
        )
        ToggleOption(
            title = stringResource(R.string.vpreview_overlay_progress),
            checked = composition.showProgress,
            onChecked = { checked -> onUpdate { it.copy(showProgress = checked) } },
        )
        TextOption(
            label = stringResource(R.string.vpreview_title_field),
            value = composition.title,
            onValueChange = { onUpdate { c -> c.copy(title = it) } },
            placeholder = stringResource(R.string.vpreview_title_hint),
        )
        TextOption(
            label = stringResource(R.string.vpreview_subtitle),
            value = composition.subtitle,
            onValueChange = { onValueChange -> onUpdate { c -> c.copy(subtitle = onValueChange) } },
            placeholder = stringResource(R.string.vpreview_subtitle_hint),
        )
        Text(
            text = stringResource(R.string.vpreview_text_position),
            style = MaterialTheme.typography.bodyLarge,
        )
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_pos_top),
                stringResource(R.string.vpreview_pos_topleft),
                stringResource(R.string.vpreview_pos_topright),
                stringResource(R.string.vpreview_pos_bottom),
                stringResource(R.string.vpreview_pos_bottomleft),
                stringResource(R.string.vpreview_pos_bottomright),
            ),
            selectedIndex = composition.overlayPosition.ordinal,
            onSelect = { index -> onUpdate { it.copy(overlayPosition = OverlayPosition.values()[index]) } },
        )
        Text(
            text = stringResource(R.string.vpreview_typography),
            style = MaterialTheme.typography.bodyLarge,
        )
        ChipOptions(
            options = listOf("S", "M", "L"),
            selectedIndex = composition.textSize.ordinal,
            onSelect = { index -> onUpdate { it.copy(textSize = OverlayTextSize.values()[index]) } },
        )
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_weight_regular),
                stringResource(R.string.vpreview_weight_bold),
            ),
            selectedIndex = composition.textWeight.ordinal,
            onSelect = { index -> onUpdate { it.copy(textWeight = OverlayTextWeight.values()[index]) } },
        )
        ChipOptions(
            options = listOf(
                stringResource(R.string.vpreview_align_start),
                stringResource(R.string.vpreview_align_center),
                stringResource(R.string.vpreview_align_end),
            ),
            selectedIndex = composition.textAlign.ordinal,
            onSelect = { index -> onUpdate { it.copy(textAlign = OverlayTextAlign.values()[index]) } },
        )
        CompositionNote(text = stringResource(R.string.vpreview_safe_note))
    }

    CompositionSection(title = stringResource(R.string.vpreview_intro_outro)) {
        ToggleOption(
            title = stringResource(R.string.vpreview_intro),
            checked = composition.introEnabled,
            onChecked = { checked -> onUpdate { it.copy(introEnabled = checked) } },
        )
        if (composition.introEnabled) {
            SliderOption(
                title = stringResource(R.string.vpreview_intro_len),
                value = composition.introDurationSec.toFloat(),
                onValueChange = { onUpdate { c -> c.copy(introDurationSec = it.toInt()) } },
                valueRange = 1f..5f,
                steps = 3,
                valueLabel = stringResource(R.string.vpreview_seconds, composition.introDurationSec),
            )
        }
        ToggleOption(
            title = stringResource(R.string.vpreview_outro),
            checked = composition.outroEnabled,
            onChecked = { checked -> onUpdate { it.copy(outroEnabled = checked) } },
        )
        if (composition.outroEnabled) {
            SliderOption(
                title = stringResource(R.string.vpreview_outro_len),
                value = composition.outroDurationSec.toFloat(),
                onValueChange = { onUpdate { c -> c.copy(outroDurationSec = it.toInt()) } },
                valueRange = 1f..5f,
                steps = 3,
                valueLabel = stringResource(R.string.vpreview_seconds, composition.outroDurationSec),
            )
            ToggleOption(
                title = stringResource(R.string.vpreview_outro_location),
                checked = composition.outroShowFinalLocation,
                onChecked = { checked -> onUpdate { it.copy(outroShowFinalLocation = checked) } },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Export dialog (Phase 7): preflight + start. The summary shows the exact
// spec the renderer will use; the encoder probe runs before anything long
// starts, so unsupported settings fail here — fast and with guidance.
// ---------------------------------------------------------------------------

@Composable
private fun ExportDialog(
    composition: VideoComposition,
    engine: AnimationEngine,
    timelineRef: String,
    onApplyFallback: (VideoResolutionPreset, VideoFps) -> Unit,
    onStartExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = remember(composition, engine) {
        composition.toExportSpec(engine.timeline, timelineRef.ifBlank { "timeline" })
    }
    var probe by remember(spec) { mutableStateOf<EncoderProbe.Result?>(null) }
    LaunchedEffect(spec) {
        probe = withContext(Dispatchers.Default) {
            EncoderProbe.check(
                spec.exportWidth,
                spec.exportHeight,
                spec.fps,
                BitratePolicy.bitrateFor(spec.exportWidth, spec.exportHeight, spec.fps),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vpreview_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.vpreview_export_body,
                        spec.exportWidth,
                        spec.exportHeight,
                        spec.fps,
                        formatMs(spec.totalVideoMs),
                        spec.eventCount,
                    ),
                )
                when (val result = probe) {
                    null -> Text(stringResource(R.string.export_probe_checking))
                    else -> if (result.ok) {
                        Text(stringResource(R.string.export_probe_ok))
                    } else {
                        Text(
                            result.error?.userMessage
                                ?: stringResource(R.string.export_probe_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        val fallback = result.fallback
                        if (fallback != null) {
                            OutlinedButton(
                                onClick = {
                                    val preset = when (maxOf(fallback.width, fallback.height)) {
                                        1920 -> VideoResolutionPreset.P1080
                                        else -> VideoResolutionPreset.P720
                                    }
                                    val fps = when (fallback.fps) {
                                        24 -> VideoFps.FPS_24
                                        60 -> VideoFps.FPS_60
                                        else -> VideoFps.FPS_30
                                    }
                                    onApplyFallback(preset, fps)
                                },
                            ) {
                                Text(
                                    stringResource(
                                        R.string.export_probe_use_fallback,
                                        fallback.label,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStartExport,
                enabled = probe?.ok == true,
            ) {
                Text(stringResource(R.string.export_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notif_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Export progress dialog: live render state from the foreground service.
// Survives rotation and navigation — the service owns the work.
// ---------------------------------------------------------------------------

@Composable
private fun ExportProgressDialog(
    onDone: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by ExportProgressBus.snapshot.collectAsState()
    val state = snapshot.state
    val progress = snapshot.progress

    // Navigate to the result exactly once when the render completes.
    var navigated by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is RenderState.Completed && !navigated) {
            navigated = true
            onCompleted()
        }
    }

    AlertDialog(
        onDismissRequest = {
            // Only dismissible when nothing is running.
            if (!ExportProgressBus.isActive()) onDone()
        },
        title = { Text(stringResource(R.string.export_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (state) {
                        is RenderState.Idle -> stringResource(R.string.export_state_idle)
                        is RenderState.Preparing ->
                            stringResource(R.string.export_state_preparing)
                        is RenderState.Rendering ->
                            stringResource(R.string.export_state_rendering)
                        is RenderState.Finalizing ->
                            stringResource(R.string.export_state_finalizing)
                        is RenderState.Completed ->
                            stringResource(R.string.export_state_completed)
                        is RenderState.Failed -> (state as RenderState.Failed).message
                        is RenderState.Cancelled ->
                            stringResource(R.string.export_state_cancelled)
                    },
                )
                val p = progress
                if (state is RenderState.Rendering && p != null && p.totalFrames > 0) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val eta = p.etaMs
                    Text(
                        stringResource(
                            R.string.export_progress_detail,
                            p.framesDone,
                            p.totalFrames,
                            (p.fraction * 100).toInt(),
                            if (p.framesPerSecond > 0) p.framesPerSecond.toInt() else 0,
                            if (eta != null) formatMs(eta) else "–",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            when {
                ExportProgressBus.isActive() -> {
                    TextButton(
                        onClick = { ExportService.cancelPhase7(context) },
                    ) {
                        Text(stringResource(R.string.export_cancel))
                    }
                }
                else -> {
                    TextButton(onClick = onDone) {
                        Text(stringResource(R.string.vpreview_export_ok))
                    }
                }
            }
        },
    )
}
