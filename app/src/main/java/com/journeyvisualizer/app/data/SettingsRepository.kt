package com.journeyvisualizer.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.VideoFps
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.composition.VideoResolutionPreset
import com.journeyvisualizer.app.settings.AppSettings
import com.journeyvisualizer.app.settings.AppTheme
import com.journeyvisualizer.app.settings.DefaultPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("jv_settings")

/** Video format presets: key -> (width, height). */
val VIDEO_FORMATS: Map<String, Pair<Int, Int>> = mapOf(
    "SQUARE_480" to (480 to 480),
    "SQUARE_720" to (720 to 720),
    "SQUARE_1080" to (1080 to 1080),
    "PORTRAIT_1080" to (1080 to 1920),
    "LANDSCAPE_1080" to (1920 to 1080),
)

/** Format key -> translatable label resource. */
val VIDEO_FORMAT_LABELS: Map<String, Int> = mapOf(
    "SQUARE_480" to R.string.fmt_square_480,
    "SQUARE_720" to R.string.fmt_square_720,
    "SQUARE_1080" to R.string.fmt_square_1080,
    "PORTRAIT_1080" to R.string.fmt_portrait_1080,
    "LANDSCAPE_1080" to R.string.fmt_landscape_1080,
)

/** Camera mode key -> translatable label resource. */
val CAMERA_LABELS: Map<String, Int> = mapOf(
    "STEADY" to R.string.camera_steady,
    "DYNAMIC" to R.string.camera_dynamic,
    "FIXED" to R.string.camera_fixed,
)

/** Pace (time warp) key -> translatable label resource. */
val TIME_WARP_LABELS: Map<String, Int> = mapOf(
    "LINEAR" to R.string.pace_linear,
    "COMPRESS_STOPS" to R.string.pace_compress,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit") // "km" | "mi"
        val DEFAULT_DURATION = intPreferencesKey("default_duration_sec")
        val VIDEO_FORMAT = stringPreferencesKey("video_format")
        val CAMERA_MODE = stringPreferencesKey("camera_mode") // "STEADY" | "DYNAMIC" | "FIXED"
        val TIME_WARP = stringPreferencesKey("time_warp") // "LINEAR" | "COMPRESS_STOPS"
        val TITLE_TEMPLATE = stringPreferencesKey("title_template")
        val LAST_IMPORT_URI = stringPreferencesKey("last_import_uri")
        val LAST_IMPORT_NAME = stringPreferencesKey("last_import_name")
        val MAP_STYLE = stringPreferencesKey("map_style") // BasemapStyle name
        val VIDEO_COMPOSITION = stringPreferencesKey("video_composition") // CompositionCodec

        // -- Phase 9: user settings --------------------------------------
        val THEME = stringPreferencesKey("p9_theme") // AppTheme.key
        val MAP_CAMERA_MODE = stringPreferencesKey("p9_map_camera_mode") // CompositionCameraMode name
        val SHOW_ROUTE_DEFAULT = booleanPreferencesKey("p9_show_route_default")
        val SHOW_START_END_DEFAULT = booleanPreferencesKey("p9_show_start_end_default")
        val DEFAULT_PRESET = stringPreferencesKey("p9_default_preset") // DefaultPreset.key
        val DEFAULT_ASPECT = stringPreferencesKey("p9_default_aspect") // VideoAspectRatio name
        val DEFAULT_RESOLUTION = stringPreferencesKey("p9_default_resolution") // VideoResolutionPreset name
        val DEFAULT_FPS = stringPreferencesKey("p9_default_fps") // VideoFps name
        val DEFAULT_ROUTE_VISIBLE = booleanPreferencesKey("p9_default_route_visible")
        val DEFAULT_MARKER_STYLE = stringPreferencesKey("p9_default_marker_style") // VideoMarkerStyle name
        val DEFAULT_START_END_MARKERS = booleanPreferencesKey("p9_default_start_end_markers")
        val DEFAULT_INTRO_OUTRO = booleanPreferencesKey("p9_default_intro_outro")
        val DEFAULT_PLAYBACK_SPEED = doublePreferencesKey("p9_default_playback_speed")
        val FOLLOW_CAMERA_DEFAULT = booleanPreferencesKey("p9_follow_camera_default")
        val AUTOPLAY_PREVIEW = booleanPreferencesKey("p9_autoplay_preview")
    }

    val distanceUnit: Flow<String> =
        context.dataStore.data.map { it[Keys.DISTANCE_UNIT] ?: "km" }
    val defaultDurationSec: Flow<Int> =
        context.dataStore.data.map { it[Keys.DEFAULT_DURATION] ?: 60 }
    val videoFormat: Flow<String> =
        context.dataStore.data.map { it[Keys.VIDEO_FORMAT] ?: "SQUARE_1080" }
    val cameraMode: Flow<String> =
        context.dataStore.data.map { it[Keys.CAMERA_MODE] ?: "STEADY" }
    val defaultTimeWarp: Flow<String> =
        context.dataStore.data.map { it[Keys.TIME_WARP] ?: "LINEAR" }
    val titleTemplate: Flow<String> =
        context.dataStore.data.map { it[Keys.TITLE_TEMPLATE] ?: "My Journey {year}" }
    val lastImportUri: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_IMPORT_URI] }
    val lastImportName: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_IMPORT_NAME] }
    val mapStyle: Flow<String?> =
        context.dataStore.data.map { it[Keys.MAP_STYLE] }
    /** Phase 6 video composition, encoded by CompositionCodec. */
    val videoComposition: Flow<String?> =
        context.dataStore.data.map { it[Keys.VIDEO_COMPOSITION] }

    suspend fun setDistanceUnit(v: String) = edit { it[Keys.DISTANCE_UNIT] = v }
    suspend fun setDefaultDurationSec(v: Int) = edit { it[Keys.DEFAULT_DURATION] = v }
    suspend fun setVideoFormat(v: String) = edit { it[Keys.VIDEO_FORMAT] = v }
    suspend fun setCameraMode(v: String) = edit { it[Keys.CAMERA_MODE] = v }
    suspend fun setDefaultTimeWarp(v: String) = edit { it[Keys.TIME_WARP] = v }
    suspend fun setTitleTemplate(v: String) = edit { it[Keys.TITLE_TEMPLATE] = v }
    suspend fun setLastImport(uri: String, name: String) = edit {
        it[Keys.LAST_IMPORT_URI] = uri
        it[Keys.LAST_IMPORT_NAME] = name
    }
    suspend fun setMapStyle(v: String) = edit { it[Keys.MAP_STYLE] = v }
    suspend fun saveVideoComposition(encoded: String) = edit { it[Keys.VIDEO_COMPOSITION] = encoded }

    // -- Phase 9: settings flows ------------------------------------------

    private fun <T> prefFlow(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data.map { it[key] ?: default }

    val theme: Flow<AppTheme> =
        context.dataStore.data.map { AppTheme.fromKey(it[Keys.THEME]) }
    val mapCameraMode: Flow<CompositionCameraMode> =
        context.dataStore.data.map {
            runCatching { CompositionCameraMode.valueOf(it[Keys.MAP_CAMERA_MODE].orEmpty()) }
                .getOrDefault(CompositionCameraMode.FOLLOW_JOURNEY)
        }
    val showRouteByDefault: Flow<Boolean> = prefFlow(Keys.SHOW_ROUTE_DEFAULT, true)
    val showStartEndByDefault: Flow<Boolean> = prefFlow(Keys.SHOW_START_END_DEFAULT, true)
    val defaultPreset: Flow<DefaultPreset> =
        context.dataStore.data.map { DefaultPreset.fromKey(it[Keys.DEFAULT_PRESET]) }
    val defaultAspect: Flow<VideoAspectRatio> =
        context.dataStore.data.map {
            runCatching { VideoAspectRatio.valueOf(it[Keys.DEFAULT_ASPECT].orEmpty()) }
                .getOrDefault(VideoAspectRatio.SIXTEEN_NINE)
        }
    val defaultResolution: Flow<VideoResolutionPreset> =
        context.dataStore.data.map {
            runCatching { VideoResolutionPreset.valueOf(it[Keys.DEFAULT_RESOLUTION].orEmpty()) }
                .getOrDefault(VideoResolutionPreset.P1080)
        }
    val defaultFps: Flow<VideoFps> =
        context.dataStore.data.map {
            runCatching { VideoFps.valueOf(it[Keys.DEFAULT_FPS].orEmpty()) }
                .getOrDefault(VideoFps.FPS_30)
        }
    val defaultRouteVisible: Flow<Boolean> = prefFlow(Keys.DEFAULT_ROUTE_VISIBLE, true)
    val defaultMarkerStyle: Flow<VideoMarkerStyle> =
        context.dataStore.data.map {
            runCatching { VideoMarkerStyle.valueOf(it[Keys.DEFAULT_MARKER_STYLE].orEmpty()) }
                .getOrDefault(VideoMarkerStyle.STANDARD)
        }
    val defaultStartEndMarkers: Flow<Boolean> = prefFlow(Keys.DEFAULT_START_END_MARKERS, true)
    val defaultIntroOutro: Flow<Boolean> = prefFlow(Keys.DEFAULT_INTRO_OUTRO, false)
    val defaultPlaybackSpeed: Flow<Double> = prefFlow(Keys.DEFAULT_PLAYBACK_SPEED, 1.0)
    val followCameraByDefault: Flow<Boolean> = prefFlow(Keys.FOLLOW_CAMERA_DEFAULT, true)
    val autoplayPreview: Flow<Boolean> = prefFlow(Keys.AUTOPLAY_PREVIEW, false)

    /** Combined settings object, read from one preferences snapshot and sanitized. */
    val appSettings: Flow<AppSettings> =
        context.dataStore.data.map { p ->
            AppSettings(
                theme = AppTheme.fromKey(p[Keys.THEME]),
                mapCameraMode = runCatching { CompositionCameraMode.valueOf(p[Keys.MAP_CAMERA_MODE].orEmpty()) }
                    .getOrDefault(CompositionCameraMode.FOLLOW_JOURNEY),
                showRouteByDefault = p[Keys.SHOW_ROUTE_DEFAULT] ?: true,
                showStartEndByDefault = p[Keys.SHOW_START_END_DEFAULT] ?: true,
                defaultPreset = DefaultPreset.fromKey(p[Keys.DEFAULT_PRESET]),
                defaultAspect = runCatching { VideoAspectRatio.valueOf(p[Keys.DEFAULT_ASPECT].orEmpty()) }
                    .getOrDefault(VideoAspectRatio.SIXTEEN_NINE),
                defaultResolution = runCatching { VideoResolutionPreset.valueOf(p[Keys.DEFAULT_RESOLUTION].orEmpty()) }
                    .getOrDefault(VideoResolutionPreset.P1080),
                defaultFps = runCatching { VideoFps.valueOf(p[Keys.DEFAULT_FPS].orEmpty()) }
                    .getOrDefault(VideoFps.FPS_30),
                defaultRouteVisible = p[Keys.DEFAULT_ROUTE_VISIBLE] ?: true,
                defaultMarkerStyle = runCatching { VideoMarkerStyle.valueOf(p[Keys.DEFAULT_MARKER_STYLE].orEmpty()) }
                    .getOrDefault(VideoMarkerStyle.STANDARD),
                defaultStartEndMarkers = p[Keys.DEFAULT_START_END_MARKERS] ?: true,
                defaultIntroOutro = p[Keys.DEFAULT_INTRO_OUTRO] ?: false,
                defaultPlaybackSpeed = p[Keys.DEFAULT_PLAYBACK_SPEED] ?: 1.0,
                followCameraByDefault = p[Keys.FOLLOW_CAMERA_DEFAULT] ?: true,
                autoplayPreview = p[Keys.AUTOPLAY_PREVIEW] ?: false,
            ).sanitized()
        }

    suspend fun setTheme(v: AppTheme) = edit { it[Keys.THEME] = v.key }
    suspend fun setMapCameraMode(v: CompositionCameraMode) = edit { it[Keys.MAP_CAMERA_MODE] = v.name }
    suspend fun setShowRouteByDefault(v: Boolean) = edit { it[Keys.SHOW_ROUTE_DEFAULT] = v }
    suspend fun setShowStartEndByDefault(v: Boolean) = edit { it[Keys.SHOW_START_END_DEFAULT] = v }
    suspend fun setDefaultPreset(v: DefaultPreset) = edit { it[Keys.DEFAULT_PRESET] = v.key }
    suspend fun setDefaultAspect(v: VideoAspectRatio) = edit { it[Keys.DEFAULT_ASPECT] = v.name }
    suspend fun setDefaultResolution(v: VideoResolutionPreset) = edit { it[Keys.DEFAULT_RESOLUTION] = v.name }
    suspend fun setDefaultFps(v: VideoFps) = edit { it[Keys.DEFAULT_FPS] = v.name }
    suspend fun setDefaultRouteVisible(v: Boolean) = edit { it[Keys.DEFAULT_ROUTE_VISIBLE] = v }
    suspend fun setDefaultMarkerStyle(v: VideoMarkerStyle) = edit { it[Keys.DEFAULT_MARKER_STYLE] = v.name }
    suspend fun setDefaultStartEndMarkers(v: Boolean) = edit { it[Keys.DEFAULT_START_END_MARKERS] = v }
    suspend fun setDefaultIntroOutro(v: Boolean) = edit { it[Keys.DEFAULT_INTRO_OUTRO] = v }
    suspend fun setDefaultPlaybackSpeed(v: Double) = edit { it[Keys.DEFAULT_PLAYBACK_SPEED] = v }
    suspend fun setFollowCameraByDefault(v: Boolean) = edit { it[Keys.FOLLOW_CAMERA_DEFAULT] = v }
    suspend fun setAutoplayPreview(v: Boolean) = edit { it[Keys.AUTOPLAY_PREVIEW] = v }

    /**
     * Resets ONLY user preferences to their defaults.
     *
     * Explicitly NOT touched (documented per the Phase 9 spec):
     * - the user's live video composition document (VIDEO_COMPOSITION) —
     *   a tuned per-video project is work, not a preference;
     * - the last-import URI/name cache (LAST_IMPORT_*) — session state;
     * - legacy pre-Phase-9 keys (distance unit, camera mode, …) — untouched.
     *
     * Videos, Room history metadata, imported files, and completed exports
     * live outside DataStore and are never affected by this function.
     */
    suspend fun resetUserPreferences() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.THEME)
            prefs.remove(Keys.MAP_CAMERA_MODE)
            prefs.remove(Keys.SHOW_ROUTE_DEFAULT)
            prefs.remove(Keys.SHOW_START_END_DEFAULT)
            prefs.remove(Keys.DEFAULT_PRESET)
            prefs.remove(Keys.DEFAULT_ASPECT)
            prefs.remove(Keys.DEFAULT_RESOLUTION)
            prefs.remove(Keys.DEFAULT_FPS)
            prefs.remove(Keys.DEFAULT_ROUTE_VISIBLE)
            prefs.remove(Keys.DEFAULT_MARKER_STYLE)
            prefs.remove(Keys.DEFAULT_START_END_MARKERS)
            prefs.remove(Keys.DEFAULT_INTRO_OUTRO)
            prefs.remove(Keys.DEFAULT_PLAYBACK_SPEED)
            prefs.remove(Keys.FOLLOW_CAMERA_DEFAULT)
            prefs.remove(Keys.AUTOPLAY_PREVIEW)
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
