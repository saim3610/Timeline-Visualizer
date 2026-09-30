package com.journeyvisualizer.app.settings

import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.composition.CompositionPresets
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.VideoComposition
import com.journeyvisualizer.app.composition.VideoFps
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.composition.VideoResolutionPreset

/**
 * Phase 9: persistent user settings, pure domain layer.
 *
 * [AppSettings] is the single source of truth for every user preference.
 * It is persisted through [com.journeyvisualizer.app.data.SettingsRepository]
 * (DataStore) and never holds large objects — only small keys/values.
 *
 * This file is pure Kotlin (no Android imports) so the defaulting and
 * reset logic is covered by JVM unit tests.
 */

/** Appearance theme choice. Stored as lowercase strings in DataStore. */
enum class AppTheme(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromKey(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Named composition preset used as the base for new videos. */
enum class DefaultPreset(val key: String) {
    TRAVEL("travel"),
    SOCIAL("social"),
    MINIMAL("minimal"),
    ;

    companion object {
        fun fromKey(key: String?): DefaultPreset =
            entries.firstOrNull { it.key == key } ?: TRAVEL
    }
}

/**
 * All user preferences with their defaults.
 *
 * These defaults mirror the Phase 6/7 renderer capabilities — every option
 * offered here is honored by real code (never a fake toggle):
 * - [mapCameraMode] maps to [CompositionCameraMode], which the video preview
 *   and the Phase 7 renderer both consume.
 * - [defaultPlaybackSpeed] must be one of the speeds the Phase 5
 *   [com.journeyvisualizer.app.animation.AnimationConfig] offers; anything
 *   else is coerced to 1.0 by [sanitized].
 */
data class AppSettings(
    // Appearance
    val theme: AppTheme = AppTheme.SYSTEM,
    // Map
    val mapCameraMode: CompositionCameraMode = CompositionCameraMode.FOLLOW_JOURNEY,
    val showRouteByDefault: Boolean = true,
    val showStartEndByDefault: Boolean = true,
    // Video
    val defaultPreset: DefaultPreset = DefaultPreset.TRAVEL,
    val defaultAspect: VideoAspectRatio = VideoAspectRatio.SIXTEEN_NINE,
    val defaultResolution: VideoResolutionPreset = VideoResolutionPreset.P1080,
    val defaultFps: VideoFps = VideoFps.FPS_30,
    val defaultRouteVisible: Boolean = true,
    val defaultMarkerStyle: VideoMarkerStyle = VideoMarkerStyle.STANDARD,
    val defaultStartEndMarkers: Boolean = true,
    val defaultIntroOutro: Boolean = false,
    // Playback
    val defaultPlaybackSpeed: Double = 1.0,
    val followCameraByDefault: Boolean = true,
    val autoplayPreview: Boolean = false,
) {
    /**
     * Returns a copy with any value the rest of the app cannot honor
     * replaced by its default. Called after loading from DataStore so a
     * corrupt or hand-edited value can never break the renderer.
     */
    fun sanitized(): AppSettings {
        val speedOk = defaultPlaybackSpeed in PLAYBACK_SPEEDS
        return if (speedOk) this else copy(defaultPlaybackSpeed = 1.0)
    }

    companion object {
        /** Speeds genuinely supported by the Phase 5 animation engine. */
        val PLAYBACK_SPEEDS: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)

        /** Resolutions the Phase 7 renderer can actually encode. */
        val RESOLUTIONS: List<VideoResolutionPreset> = listOf(
            VideoResolutionPreset.P720,
            VideoResolutionPreset.P1080,
            VideoResolutionPreset.P1440,
            VideoResolutionPreset.P2160,
        )

        /** Frame rates the Phase 7 render clock supports. */
        val FRAME_RATES: List<VideoFps> = listOf(
            VideoFps.FPS_24,
            VideoFps.FPS_30,
            VideoFps.FPS_60,
        )

        val DEFAULT = AppSettings()
    }
}

/**
 * Builds the [VideoComposition] used for a *new* video from the user's
 * stored defaults. Starts from the chosen named preset, then applies the
 * individual default overrides. The user's live composition document is
 * never rewritten behind their back — this is only used when a new video
 * is started or the composition is explicitly reset.
 */
fun defaultVideoComposition(settings: AppSettings): VideoComposition {
    val s = settings.sanitized()
    val base = when (s.defaultPreset) {
        DefaultPreset.TRAVEL -> CompositionPresets.travel()
        DefaultPreset.SOCIAL -> CompositionPresets.social()
        DefaultPreset.MINIMAL -> CompositionPresets.minimal()
    }
    return base.copy(
        aspectRatio = s.defaultAspect,
        resolutionPreset = s.defaultResolution,
        fps = s.defaultFps,
        cameraMode = s.mapCameraMode,
        routeVisible = s.defaultRouteVisible,
        markerStyle = s.defaultMarkerStyle,
        startEndMarkers = s.defaultStartEndMarkers,
        introEnabled = s.defaultIntroOutro,
        outroEnabled = s.defaultIntroOutro,
    )
}
