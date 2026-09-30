package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.animation.AnimationTimeline
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.VideoMarkerStyle

/**
 * Clean handoff object for the Phase 7 renderer (Phase 6).
 *
 * Contains everything the deterministic exporter needs: a *reference* to
 * the normalized timeline/animation data (never a copy of the JSON),
 * plus the full composition. Both preview and export consume the same
 * timeline, animation model, camera model, and composition settings, so
 * the exported video matches the preview.
 *
 * No encoding happens here — Phase 7 only.
 */
data class ExportSpec(
    /** Reference to the imported timeline (e.g. file name); not the data. */
    val timelineRef: String,
    /** Number of animation events the video covers. */
    val eventCount: Int,
    /** Requested journey-animation length (ms) from the duration mode. */
    val animationTargetMs: Long,
    /** Actual compressed animation length (ms) at 1×. */
    val animationTotalMs: Long,
    val aspectRatio: VideoAspectRatio,
    val exportWidth: Int,
    val exportHeight: Int,
    val fps: Int,
    val introMs: Long,
    val outroMs: Long,
    /** intro + animation + outro (ms). */
    val totalVideoMs: Long,
    val mapStyle: BasemapStyle,
    val cameraMode: CompositionCameraMode,
    val routeDrawMode: RouteDrawMode,
    val routeVisible: Boolean,
    val routeWidthScale: Float,
    val routeOpacity: Float,
    val markerStyle: VideoMarkerStyle,
    val startEndMarkers: Boolean,
    val showLocation: Boolean,
    val showDateTime: Boolean,
    val showProgress: Boolean,
    val title: String,
    val subtitle: String,
    val overlayPosition: OverlayPosition,
    val textSize: OverlayTextSize,
    val textWeight: OverlayTextWeight,
    val textAlign: OverlayTextAlign,
    val outroShowFinalLocation: Boolean,
)

/**
 * Builds the Phase 7 export spec from a composition and its timeline.
 * Deterministic: same composition + same timeline ⇒ equal [ExportSpec].
 */
fun VideoComposition.toExportSpec(
    timeline: AnimationTimeline,
    timelineRef: String,
): ExportSpec {
    val (w, h) = exportSize()
    val animTotal = timeline.totalDurationMs
    return ExportSpec(
        timelineRef = timelineRef,
        eventCount = timeline.eventCount,
        animationTargetMs = animationTargetMs(),
        animationTotalMs = animTotal,
        aspectRatio = aspectRatio,
        exportWidth = w,
        exportHeight = h,
        fps = fps.value,
        introMs = introMs(),
        outroMs = outroMs(),
        totalVideoMs = introMs() + animTotal + outroMs(),
        mapStyle = mapStyle,
        cameraMode = cameraMode,
        routeDrawMode = routeDrawMode,
        routeVisible = routeVisible,
        routeWidthScale = routeWidthScale,
        routeOpacity = routeOpacity,
        markerStyle = markerStyle,
        startEndMarkers = startEndMarkers,
        showLocation = showLocation,
        showDateTime = showDateTime,
        showProgress = showProgress,
        title = title,
        subtitle = subtitle,
        overlayPosition = overlayPosition,
        textSize = textSize,
        textWeight = textWeight,
        textAlign = textAlign,
        outroShowFinalLocation = outroShowFinalLocation,
    )
}
