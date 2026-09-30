package com.journeyvisualizer.app.composition

import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.map.VideoMarkerStyle

/**
 * Video composition state (Phase 6).
 *
 * Everything the future Phase 7 renderer needs to reproduce the preview,
 * without touching the timeline, GeoNames results, or animation data.
 * Pure Kotlin — no Android dependencies — so it is unit-testable on the JVM
 * and serializable for persistence.
 *
 * Only settings the renderer can actually honor are modeled here.
 */
enum class VideoAspectRatio(val w: Int, val h: Int) {
    SIXTEEN_NINE(16, 9),
    NINE_SIXTEEN(9, 16),
    ONE_ONE(1, 1);

    /** Width ÷ height as a float, for layout. */
    val ratio: Float get() = w.toFloat() / h.toFloat()

    val isPortrait: Boolean get() = h > w
    val isSquare: Boolean get() = w == h
}

enum class VideoResolutionPreset {
    P720,
    P1080,
    P1440,
    P2160,
}

/**
 * Export dimensions for a preset + aspect ratio. The long edge is the
 * preset's pixel count (720/1080/1440/2160); the short edge follows the
 * aspect ratio exactly (all divide evenly: 16:9 × 720 = 1280, etc.).
 */
fun VideoResolutionPreset.exportSize(aspect: VideoAspectRatio): Pair<Int, Int> {
    val long = when (this) {
        VideoResolutionPreset.P720 -> 720
        VideoResolutionPreset.P1080 -> 1080
        VideoResolutionPreset.P1440 -> 1440
        VideoResolutionPreset.P2160 -> 2160
    }
    return when (aspect) {
        VideoAspectRatio.SIXTEEN_NINE -> (long * 16 / 9) to long
        VideoAspectRatio.NINE_SIXTEEN -> long to (long * 16 / 9)
        VideoAspectRatio.ONE_ONE -> long to long
    }
}

enum class VideoFps(val value: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60),
}

/**
 * How the journey's real duration is compressed into video time.
 * Compression is deterministic (Phase 5 [AnimationConfig]); events are
 * never dropped to fit a duration — only time is compressed.
 */
enum class VideoDurationMode {
    /** Phase 5 default: ~90 seconds of journey animation. */
    AUTO,
    /** ~30 seconds. */
    SHORT,
    /** ~60 seconds. */
    MEDIUM,
    /** ~180 seconds. */
    LONG,
    /** User-chosen total video length; the journey portion is derived. */
    CUSTOM,
}

enum class CompositionCameraMode {
    /** Camera follows the moving marker (Phase 5 follow logic). */
    FOLLOW_JOURNEY,
    /** Camera stays fitted on the complete route; no tracking. */
    FIXED_OVERVIEW,
    /**
     * Follows the marker with a wider dead-zone and calmer recentering —
     * deterministic, driven by the same Phase 5 engine.
     */
    SMART_FOLLOW,
}

enum class OverlayPosition {
    TOP,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

enum class OverlayTextSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.25f),
}

enum class OverlayTextWeight {
    REGULAR,
    BOLD,
}

enum class OverlayTextAlign {
    START,
    CENTER,
    END,
}

data class VideoComposition(
    // -- Video ---------------------------------------------------------
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.SIXTEEN_NINE,
    val resolutionPreset: VideoResolutionPreset = VideoResolutionPreset.P1080,
    val fps: VideoFps = VideoFps.FPS_30,
    val durationMode: VideoDurationMode = VideoDurationMode.AUTO,
    /** Total video length in seconds for CUSTOM mode (10..600). */
    val customDurationSec: Int = 90,
    // -- Map -----------------------------------------------------------
    /** Real Phase 4 basemap styles only — never a fake style. */
    val mapStyle: BasemapStyle = BasemapStyle.STANDARD,
    val cameraMode: CompositionCameraMode = CompositionCameraMode.FOLLOW_JOURNEY,
    val routeDrawMode: RouteDrawMode = RouteDrawMode.PROGRESSIVE,
    val routeVisible: Boolean = true,
    /** 0.5..2.0 multiplier on the route stroke width. */
    val routeWidthScale: Float = 1f,
    /** 0.2..1.0 route opacity. */
    val routeOpacity: Float = 1f,
    val markerStyle: VideoMarkerStyle = VideoMarkerStyle.STANDARD,
    val startEndMarkers: Boolean = true,
    // -- Overlay -------------------------------------------------------
    val showLocation: Boolean = true,
    val showDateTime: Boolean = true,
    val showProgress: Boolean = true,
    /** User-controlled; never auto-invented. Empty = hidden. */
    val title: String = "",
    val subtitle: String = "",
    val overlayPosition: OverlayPosition = OverlayPosition.TOP_LEFT,
    val textSize: OverlayTextSize = OverlayTextSize.MEDIUM,
    val textWeight: OverlayTextWeight = OverlayTextWeight.BOLD,
    val textAlign: OverlayTextAlign = OverlayTextAlign.START,
    // -- Intro / outro ---------------------------------------------------
    val introEnabled: Boolean = false,
    /** 1..5 seconds. */
    val introDurationSec: Int = 2,
    val outroEnabled: Boolean = false,
    /** 1..5 seconds. */
    val outroDurationSec: Int = 2,
    val outroShowFinalLocation: Boolean = true,
) {
    companion object {
        val DEFAULT = VideoComposition()
    }

    /** Intro length in ms (0 when disabled). */
    fun introMs(): Long = if (introEnabled) introDurationSec.coerceIn(1, 5) * 1000L else 0L

    /** Outro length in ms (0 when disabled). */
    fun outroMs(): Long = if (outroEnabled) outroDurationSec.coerceIn(1, 5) * 1000L else 0L

    /**
     * Target length of the *journey animation* portion in ms.
     *
     * For CUSTOM the user picks the total video length; the journey
     * portion is what remains after intro/outro (at least 10 s). The
     * original timestamps stay correct — only compression changes.
     */
    fun animationTargetMs(): Long = when (durationMode) {
        VideoDurationMode.AUTO -> 90_000L
        VideoDurationMode.SHORT -> 30_000L
        VideoDurationMode.MEDIUM -> 60_000L
        VideoDurationMode.LONG -> 180_000L
        VideoDurationMode.CUSTOM -> {
            val total = customDurationSec.coerceIn(10, 600)
            val journey = total - introMs() / 1000 - outroMs() / 1000
            journey.coerceAtLeast(10) * 1000L
        }
    }

    /** Export pixel dimensions for the selected preset + aspect ratio. */
    fun exportSize(): Pair<Int, Int> = resolutionPreset.exportSize(aspectRatio)

    // -- Serialization (pure Kotlin; persistence layer stores the string) --

    fun toMap(): Map<String, String> = mapOf(
        "aspectRatio" to aspectRatio.name,
        "resolutionPreset" to resolutionPreset.name,
        "fps" to fps.name,
        "durationMode" to durationMode.name,
        "customDurationSec" to customDurationSec.toString(),
        "mapStyle" to mapStyle.name,
        "cameraMode" to cameraMode.name,
        "routeDrawMode" to routeDrawMode.name,
        "routeVisible" to routeVisible.toString(),
        "routeWidthScale" to routeWidthScale.toString(),
        "routeOpacity" to routeOpacity.toString(),
        "markerStyle" to markerStyle.name,
        "startEndMarkers" to startEndMarkers.toString(),
        "showLocation" to showLocation.toString(),
        "showDateTime" to showDateTime.toString(),
        "showProgress" to showProgress.toString(),
        "title" to title,
        "subtitle" to subtitle,
        "overlayPosition" to overlayPosition.name,
        "textSize" to textSize.name,
        "textWeight" to textWeight.name,
        "textAlign" to textAlign.name,
        "introEnabled" to introEnabled.toString(),
        "introDurationSec" to introDurationSec.toString(),
        "outroEnabled" to outroEnabled.toString(),
        "outroDurationSec" to outroDurationSec.toString(),
        "outroShowFinalLocation" to outroShowFinalLocation.toString(),
    )
}

/**
 * Restores a [VideoComposition] from [VideoComposition.toMap].
 * Unknown or missing values fall back to defaults — never throws.
 */
fun videoCompositionFromMap(map: Map<String, String>): VideoComposition {
    fun <T : Enum<T>> enumOr(name: String?, values: Array<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback
    val d = VideoComposition.DEFAULT
    return VideoComposition(
        aspectRatio = enumOr(map["aspectRatio"], VideoAspectRatio.values(), d.aspectRatio),
        resolutionPreset = enumOr(map["resolutionPreset"], VideoResolutionPreset.values(), d.resolutionPreset),
        fps = enumOr(map["fps"], VideoFps.values(), d.fps),
        durationMode = enumOr(map["durationMode"], VideoDurationMode.values(), d.durationMode),
        customDurationSec = map["customDurationSec"]?.toIntOrNull() ?: d.customDurationSec,
        mapStyle = enumOr(map["mapStyle"], BasemapStyle.values(), d.mapStyle),
        cameraMode = enumOr(map["cameraMode"], CompositionCameraMode.values(), d.cameraMode),
        routeDrawMode = enumOr(map["routeDrawMode"], RouteDrawMode.values(), d.routeDrawMode),
        routeVisible = map["routeVisible"]?.toBooleanStrictOrNull() ?: d.routeVisible,
        routeWidthScale = map["routeWidthScale"]?.toFloatOrNull() ?: d.routeWidthScale,
        routeOpacity = map["routeOpacity"]?.toFloatOrNull() ?: d.routeOpacity,
        markerStyle = enumOr(map["markerStyle"], VideoMarkerStyle.values(), d.markerStyle),
        startEndMarkers = map["startEndMarkers"]?.toBooleanStrictOrNull() ?: d.startEndMarkers,
        showLocation = map["showLocation"]?.toBooleanStrictOrNull() ?: d.showLocation,
        showDateTime = map["showDateTime"]?.toBooleanStrictOrNull() ?: d.showDateTime,
        showProgress = map["showProgress"]?.toBooleanStrictOrNull() ?: d.showProgress,
        title = map["title"] ?: d.title,
        subtitle = map["subtitle"] ?: d.subtitle,
        overlayPosition = enumOr(map["overlayPosition"], OverlayPosition.values(), d.overlayPosition),
        textSize = enumOr(map["textSize"], OverlayTextSize.values(), d.textSize),
        textWeight = enumOr(map["textWeight"], OverlayTextWeight.values(), d.textWeight),
        textAlign = enumOr(map["textAlign"], OverlayTextAlign.values(), d.textAlign),
        introEnabled = map["introEnabled"]?.toBooleanStrictOrNull() ?: d.introEnabled,
        introDurationSec = map["introDurationSec"]?.toIntOrNull() ?: d.introDurationSec,
        outroEnabled = map["outroEnabled"]?.toBooleanStrictOrNull() ?: d.outroEnabled,
        outroDurationSec = map["outroDurationSec"]?.toIntOrNull() ?: d.outroDurationSec,
        outroShowFinalLocation = map["outroShowFinalLocation"]?.toBooleanStrictOrNull()
            ?: d.outroShowFinalLocation,
    )
}

/**
 * String codec for DataStore persistence: `k=v;k=v` with URL-encoding so
 * free-text titles survive. Pure Kotlin; decode never throws.
 */
object CompositionCodec {
    fun encode(c: VideoComposition): String =
        c.toMap().entries.joinToString(";") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

    fun decode(s: String): VideoComposition {
        if (s.isBlank()) return VideoComposition.DEFAULT
        val map = s.split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        return videoCompositionFromMap(map)
    }
}
