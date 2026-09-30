package com.journeyvisualizer.app.ui.phase1.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.CameraFollowMode
import com.journeyvisualizer.app.animation.PlaybackState
import com.journeyvisualizer.app.map.toMapPoint
import com.journeyvisualizer.app.data.geo.MatchQuality
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.InteractiveMapController
import com.journeyvisualizer.app.map.OsmMapController
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.toMapPoints
import com.journeyvisualizer.app.ui.phase1.ImportState
import com.journeyvisualizer.app.ui.phase1.ImportSummary
import com.journeyvisualizer.app.ui.phase1.MapStyle
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.components.AnimationPanel
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.LocationDetailDialog
import com.journeyvisualizer.app.ui.phase1.components.MapPin
import com.journeyvisualizer.app.ui.phase1.components.MapPreview
import com.journeyvisualizer.app.ui.phase1.components.SectionHeader
import com.journeyvisualizer.app.ui.phase1.components.SmallRadius
import com.journeyvisualizer.app.ui.phase1.map.TimelineMap
import com.journeyvisualizer.app.ui.util.formatDateTime

private val EventDotColors = listOf(
    Color(0xFF16A34A),
    Color(0xFF2E9BF0),
    Color(0xFF8B5CF6),
    Color(0xFFF97316),
)

// ---------------------------------------------------------------------------
// Screen 6 — Timeline Preview: real interactive map + event timeline.
// ---------------------------------------------------------------------------

/**
 * Timeline Preview with the Phase 4 real map.
 *
 * Layout (§21): compact summary on top, large interactive map in the center,
 * event list at the bottom. The map renders the user's ACTUAL timeline
 * coordinates (osmdroid, real tiles) — never mock positions.
 *
 * Timeline ↔ map sync uses stable point indexes: tapping an event row moves
 * the map camera; tapping a marker opens the same detail dialog as the row.
 */
@Composable
fun TimelinePreviewScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onMapStyle: () -> Unit,
    onVideoPreview: () -> Unit,
) {
    val context = LocalContext.current
    // Restore the persisted basemap style and user settings once per screen entry.
    LaunchedEffect(Unit) {
        ux.loadPersistedMapStyle(context)
        ux.loadPersistedSettings(context)
    }

    val summary = (ux.importState as? ImportState.Ready)?.summary
    val engine = ux.animationEngine
    // Hoisted so the Phase 5 animation driver can reach the same controller
    // the map composable uses. Memoized points: the controller diffs by
    // reference, so this must be stable across recompositions.
    val mapController = remember { OsmMapController(context.applicationContext) }
    // Phase 9: apply the user's map defaults (route / start-end markers) to
    // this screen's controller. Re-applies when the stored defaults change.
    val mapDefaults = ux.appSettings
    LaunchedEffect(mapDefaults) {
        mapController.setRouteAppearance(mapDefaults.showRouteByDefault, 1f, 1f)
        mapController.setStartEndMarkersVisible(mapDefaults.showStartEndByDefault)
    }
    // Animation order is chronological; stable indexes keep marker/list sync.
    val mapPoints = remember(summary, engine) {
        engine?.timeline?.points?.map { it.toMapPoint() }
            ?: summary?.resolvedPoints?.toMapPoints()
            ?: emptyList()
    }
    // Sampled pins for the event list (real imports and sample data alike).
    val pins = ux.previewPins()
    // Stable timeline index selected for the location-details dialog.
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    val basemap = remember(ux.mapStyle) { BasemapStyle.valueOf(ux.mapStyle.name) }

    // Pause playback when the app goes to background (lifecycle-aware).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) engine?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Phase 5: drive the map overlays from the animation engine.
    if (engine != null) {
        AnimationDriver(
            engine = engine,
            controller = mapController,
            routeMode = ux.routeDrawMode,
        )
    }

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.tlpreview_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // -- Compact timeline summary ------------------------------------
            TimelineSummaryHeader(
                summary = summary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(8.dp))

            // -- Real interactive map -----------------------------------------
            if (mapPoints.isNotEmpty()) {
                TimelineMap(
                    controller = mapController,
                    points = mapPoints,
                    style = basemap,
                    selectedIndex = ux.selectedMapIndex,
                    onMarkerClick = { index ->
                        ux.selectMapPoint(index)
                        detailIndex = index
                    },
                    onStyleClick = onMapStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                )
            } else {
                EmptyMapCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // -- Phase 5 journey playback --------------------------------------
            if (engine != null) {
                SectionHeader(
                    title = stringResource(R.string.anim_panel_title),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                AnimationPanel(
                    engine = engine,
                    routeMode = ux.routeDrawMode,
                    onRouteModeChange = { ux.setRouteDrawMode(it) },
                    onRecenter = {
                        engine.setFollowMode(CameraFollowMode.FOLLOW)
                        val f = engine.frame.value
                        mapController.recenterOn(f.lat, f.lng)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
            } else if (summary != null) {
                Text(
                    text = stringResource(R.string.anim_no_animation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            SectionHeader(
                title = stringResource(R.string.tlpreview_events),
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                pins.forEachIndexed { i, event ->
                    val pointIndex = event.pointIndex
                    TimelineEventRow(
                        event = event,
                        dotColor = EventDotColors[i % EventDotColors.size],
                        isFirst = i == 0,
                        isLast = i == pins.lastIndex,
                        highlighted = pointIndex >= 0 && ux.selectedMapIndex == pointIndex,
                        // Real pins select the same timeline point on the map
                        // and open details; sample pins are not tappable.
                        onClick = event.location?.let {
                            {
                                ux.selectMapPoint(pointIndex)
                                detailIndex = pointIndex
                            }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Phase 6 entry: professional video preview & composition.
            OutlinedButton(
                onClick = onVideoPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                enabled = engine != null,
            ) {
                Text(text = stringResource(R.string.tlpreview_video_preview))
            }
            Spacer(Modifier.height(20.dp))
        }

        // Location details for the tapped marker / event row.
        // Looked up by stable timeline index (points are chronologically
        // ordered, so positional lookup would be wrong).
        val selectedPoint = detailIndex?.let { id -> mapPoints.find { it.index == id } }
        if (selectedPoint != null) {
            LocationDetailDialog(
                location = selectedPoint.location
                    ?: unresolvedLocation(selectedPoint.lat, selectedPoint.lng),
                dateTime = formatDateTime(selectedPoint.timeMs),
                onDismiss = {
                    detailIndex = null
                    ux.selectMapPoint(null)
                },
            )
        }
    }
}

/**
 * Phase 5 animation driver: applies the engine's frames to the map.
 *
 * The engine owns playback state and timing; this composable is the only
 * place that translates frames into [InteractiveMapController] calls, so
 * animation logic never lives in the UI layer itself.
 */
@Composable
private fun AnimationDriver(
    engine: AnimationEngine,
    controller: InteractiveMapController,
    routeMode: RouteDrawMode,
) {
    val frame by engine.frame.collectAsState()
    val followMode by engine.followMode.collectAsState()
    val state by engine.state.collectAsState()

    // The user grabbing the map suspends camera-follow; the Recenter button
    // in the panel resumes it.
    DisposableEffect(engine, controller) {
        controller.setOnUserInteractionListener {
            engine.setFollowMode(CameraFollowMode.FREE)
        }
        onDispose { controller.setOnUserInteractionListener(null) }
    }

    // Route visualization mode is declarative.
    LaunchedEffect(routeMode) { controller.setRouteDrawMode(routeMode) }

    // Per frame: animated marker, progressive route, throttled camera follow.
    // The controller implementations are cheap/incremental per frame.
    LaunchedEffect(frame) {
        controller.setAnimatedMarkerPosition(frame.lat, frame.lng)
        controller.setAnimatedRouteProgress(frame.traveledPointCount, frame.lat, frame.lng)
        if (followMode == CameraFollowMode.FOLLOW &&
            (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED)
        ) {
            controller.followAnimatedMarker(frame.lat, frame.lng)
        }
    }
}

/** Compact real-stats header; falls back to sample wording without import. */
@Composable
private fun TimelineSummaryHeader(
    summary: ImportSummary?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = summary?.fileName ?: stringResource(R.string.tlpreview_sample_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (summary != null) {
                stringResource(
                    R.string.tlpreview_summary_real,
                    summary.validPoints,
                    summary.resolvedCount,
                )
            } else {
                stringResource(R.string.tlpreview_summary_sample)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Honest empty state when there is no timeline to draw. */
@Composable
private fun EmptyMapCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = null,
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.map_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.map_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}


@Composable
fun TimelineEventRow(
    event: MapPin,
    dotColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    /** When non-null the row is tappable (opens location details). */
    onClick: (() -> Unit)? = null,
    /** Highlights the row when its point is selected on the map (Phase 4). */
    highlighted: Boolean = false,
) {
    val location = event.location
    val approximate = location?.matchQuality == MatchQuality.APPROXIMATE
    val rowModifier = if (highlighted) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(vertical = 4.dp)
    } else {
        modifier.fillMaxWidth()
    }
    Row(modifier = rowModifier) {
        // Timeline rail.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (isFirst) 18.dp else 26.dp)
                    .background(
                        if (isFirst) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (isLast) 18.dp else 26.dp)
                    .background(
                        if (isLast) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Event card.
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
            shape = RoundedCornerShape(SmallRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = (if (approximate) "≈ " else "") + event.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = event.dateTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Resolved country, or an honest fallback — never invented.
                    val detail = location?.country
                        ?: location?.let { stringResource(R.string.location_unavailable_short) }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                MapPreview(
                    style = MapStyle.SATELLITE,
                    events = listOf(event),
                    showRoute = false,
                    showLabels = false,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}


/** Human-readable map style name, shared by settings/history. */
@Composable
fun mapStyleName(style: MapStyle): String = when (style) {
    MapStyle.STANDARD -> stringResource(R.string.style_standard)
    MapStyle.SATELLITE -> stringResource(R.string.style_satellite)
    MapStyle.HYBRID -> stringResource(R.string.style_hybrid)
    MapStyle.TERRAIN -> stringResource(R.string.style_terrain)
}
