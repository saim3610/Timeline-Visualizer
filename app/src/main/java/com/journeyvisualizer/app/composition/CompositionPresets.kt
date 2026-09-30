package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.VideoMarkerStyle

/**
 * One-tap composition presets (Phase 6).
 *
 * Presets only configure existing [VideoComposition] settings — they never
 * duplicate rendering logic and never invent data.
 */
object CompositionPresets {

    /** Balanced 16:9 travel video: location + date overlays, route, markers. */
    fun travel(): VideoComposition = VideoComposition(
        aspectRatio = VideoAspectRatio.SIXTEEN_NINE,
        resolutionPreset = VideoResolutionPreset.P1080,
        fps = VideoFps.FPS_30,
        durationMode = VideoDurationMode.AUTO,
        mapStyle = BasemapStyle.STANDARD,
        cameraMode = CompositionCameraMode.FOLLOW_JOURNEY,
        routeDrawMode = RouteDrawMode.PROGRESSIVE,
        routeVisible = true,
        markerStyle = VideoMarkerStyle.STANDARD,
        startEndMarkers = true,
        showLocation = true,
        showDateTime = true,
        showProgress = true,
        overlayPosition = OverlayPosition.TOP_LEFT,
        textSize = OverlayTextSize.MEDIUM,
    )

    /** 9:16 social video: big location overlay, safe margins applied by aspect. */
    fun social(): VideoComposition = VideoComposition(
        aspectRatio = VideoAspectRatio.NINE_SIXTEEN,
        resolutionPreset = VideoResolutionPreset.P1080,
        fps = VideoFps.FPS_30,
        durationMode = VideoDurationMode.SHORT,
        mapStyle = BasemapStyle.STANDARD,
        cameraMode = CompositionCameraMode.SMART_FOLLOW,
        routeDrawMode = RouteDrawMode.PROGRESSIVE,
        routeVisible = true,
        markerStyle = VideoMarkerStyle.HIGHLIGHTED,
        startEndMarkers = true,
        showLocation = true,
        showDateTime = true,
        showProgress = true,
        overlayPosition = OverlayPosition.TOP,
        textSize = OverlayTextSize.LARGE,
        textAlign = OverlayTextAlign.CENTER,
    )

    /** Minimal 16:9: route + moving marker, no text overlays. */
    fun minimal(): VideoComposition = VideoComposition(
        aspectRatio = VideoAspectRatio.SIXTEEN_NINE,
        resolutionPreset = VideoResolutionPreset.P1080,
        fps = VideoFps.FPS_30,
        durationMode = VideoDurationMode.AUTO,
        mapStyle = BasemapStyle.STANDARD,
        cameraMode = CompositionCameraMode.FOLLOW_JOURNEY,
        routeDrawMode = RouteDrawMode.PROGRESSIVE,
        routeVisible = true,
        markerStyle = VideoMarkerStyle.MINIMAL_DOT,
        startEndMarkers = false,
        showLocation = false,
        showDateTime = false,
        showProgress = false,
        introEnabled = false,
        outroEnabled = false,
    )

    /** All presets, for UI listing. */
    fun all(): List<Pair<String, VideoComposition>> = listOf(
        "Travel" to travel(),
        "Social" to social(),
        "Minimal" to minimal(),
    )
}
