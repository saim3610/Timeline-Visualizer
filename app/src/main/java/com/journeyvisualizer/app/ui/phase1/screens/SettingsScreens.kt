package com.journeyvisualizer.app.ui.phase1.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyvisualizer.app.BuildConfig
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.VideoFps
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.composition.VideoResolutionPreset
import com.journeyvisualizer.app.settings.AppSettings
import com.journeyvisualizer.app.settings.AppTheme
import com.journeyvisualizer.app.settings.DefaultPreset
import com.journeyvisualizer.app.settings.StorageOwnership
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.SettingsViewModel
import com.journeyvisualizer.app.ui.phase1.components.AppLogo
import com.journeyvisualizer.app.ui.phase1.components.CardRadius
import com.journeyvisualizer.app.ui.phase1.components.ConfirmDialog
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.SectionHeader
import com.journeyvisualizer.app.ui.phase1.components.SettingRow

// ---------------------------------------------------------------------------
// Shared settings building blocks.
// ---------------------------------------------------------------------------

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** A settings row with a leading icon, title, optional subtitle, and a Switch. */
@Composable
private fun SwitchSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private data class ChoiceOption(
    val label: String,
    val sublabel: String? = null,
    val enabled: Boolean = true,
)

/** Single-choice dialog with radio rows (48dp+ touch targets, disabled states). */
@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == selectedIndex,
                                enabled = option.enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            enabled = option.enabled,
                            onClick = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                            if (option.sublabel != null) {
                                Text(
                                    text = option.sublabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun speedLabel(speed: Double): String = when (speed) {
    0.25 -> "0.25×"
    0.5 -> "0.5×"
    1.0 -> "1×"
    2.0 -> "2×"
    4.0 -> "4×"
    8.0 -> "8×"
    else -> "${speed}×"
}

@Composable
private fun themeLabel(theme: AppTheme): String = stringResource(
    when (theme) {
        AppTheme.SYSTEM -> R.string.theme_system
        AppTheme.LIGHT -> R.string.theme_light
        AppTheme.DARK -> R.string.theme_dark
    },
)

@Composable
private fun cameraModeLabel(mode: CompositionCameraMode): String = stringResource(
    when (mode) {
        CompositionCameraMode.FOLLOW_JOURNEY -> R.string.camera_follow_journey
        CompositionCameraMode.SMART_FOLLOW -> R.string.camera_smart_follow
        CompositionCameraMode.FIXED_OVERVIEW -> R.string.camera_fixed_overview
    },
)

@Composable
private fun presetLabel(preset: DefaultPreset): String = stringResource(
    when (preset) {
        DefaultPreset.TRAVEL -> R.string.preset_travel
        DefaultPreset.SOCIAL -> R.string.preset_social
        DefaultPreset.MINIMAL -> R.string.preset_minimal
    },
)

private fun aspectLabel(aspect: VideoAspectRatio): String = when (aspect) {
    VideoAspectRatio.SIXTEEN_NINE -> "16:9"
    VideoAspectRatio.NINE_SIXTEEN -> "9:16"
    VideoAspectRatio.ONE_ONE -> "1:1"
}

@Composable
private fun resolutionLabel(res: VideoResolutionPreset): String = stringResource(
    when (res) {
        VideoResolutionPreset.P720 -> R.string.res_720p
        VideoResolutionPreset.P1080 -> R.string.res_1080p
        VideoResolutionPreset.P1440 -> R.string.res_1440p
        VideoResolutionPreset.P2160 -> R.string.res_4k
    },
)

private fun fpsLabel(fps: VideoFps): String = "${fps.value} fps"

@Composable
private fun markerStyleLabel(style: VideoMarkerStyle): String = stringResource(
    when (style) {
        VideoMarkerStyle.STANDARD -> R.string.marker_standard
        VideoMarkerStyle.MINIMAL_DOT -> R.string.marker_minimal_dot
        VideoMarkerStyle.HIGHLIGHTED -> R.string.marker_highlighted
        VideoMarkerStyle.HIDDEN -> R.string.marker_hidden
    },
)

@Composable
private fun cacheDirLabel(dir: String): String = when {
    dir == "video_thumbs" -> stringResource(R.string.storage_cache_thumbs)
    dir == "tiles" -> stringResource(R.string.storage_cache_tiles)
    dir.startsWith(StorageOwnership.RENDER_TILE_CACHE_PREFIX) ->
        stringResource(R.string.storage_cache_render)
    else -> dir
}

// ---------------------------------------------------------------------------
// Screen 13 — Settings (Phase 9 rewrite)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsVm: SettingsViewModel,
    ux: Phase1ViewModel,
    onMapStyle: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    // Phase 9: make sure the persisted map style and user settings are loaded
    // before the screen renders current defaults.
    LaunchedEffect(Unit) {
        ux.loadPersistedMapStyle(context)
        ux.loadPersistedSettings(context)
    }
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val storageState by settingsVm.storage.collectAsStateWithLifecycle()
    val cacheClearedBytes by settingsVm.cacheClearedBytes.collectAsStateWithLifecycle()
    val resetDone by settingsVm.resetDone.collectAsStateWithLifecycle()
    val supports4k by settingsVm.supports4k.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val cacheEmptyText = stringResource(R.string.storage_nothing_to_clear)
    val cacheClearedFormat = stringResource(R.string.storage_cleared)
    LaunchedEffect(cacheClearedBytes) {
        val bytes = cacheClearedBytes ?: return@LaunchedEffect
        // Non-composable coroutine context: format via the captured context,
        // not stringResource().
        snackbar.showSnackbar(
            if (bytes > 0) cacheClearedFormat.format(StorageOwnership.formatBytes(bytes))
            else cacheEmptyText,
        )
        settingsVm.consumeCacheCleared()
    }
    val resetDoneText = stringResource(R.string.prefs_reset_done)
    LaunchedEffect(resetDone) {
        if (resetDone) {
            snackbar.showSnackbar(resetDoneText)
            settingsVm.consumeResetDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.prefs_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // -- Appearance -------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_appearance))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.SettingsBrightness,
                    title = stringResource(R.string.prefs_theme),
                    value = themeLabel(settings.theme),
                    onClick = { dialog = SettingsDialog.Theme },
                )
            }

            // -- Map ---------------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_map))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.Map,
                    title = stringResource(R.string.prefs_map_style),
                    value = mapStyleName(ux.mapStyle),
                    onClick = onMapStyle,
                )
                SettingRow(
                    icon = Icons.Filled.CameraAlt,
                    title = stringResource(R.string.prefs_map_camera),
                    value = cameraModeLabel(settings.mapCameraMode),
                    onClick = { dialog = SettingsDialog.CameraMode },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.Route,
                    title = stringResource(R.string.prefs_show_route_default),
                    subtitle = stringResource(R.string.prefs_show_route_default_sub),
                    checked = settings.showRouteByDefault,
                    onCheckedChange = { settingsVm.update { setShowRouteByDefault(it) } },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.LocationOn,
                    title = stringResource(R.string.prefs_show_start_end_default),
                    subtitle = stringResource(R.string.prefs_show_start_end_default_sub),
                    checked = settings.showStartEndByDefault,
                    onCheckedChange = { settingsVm.update { setShowStartEndByDefault(it) } },
                )
            }

            // -- Video defaults ------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_video_defaults))
            Text(
                text = stringResource(R.string.prefs_video_defaults_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.prefs_default_preset),
                    value = presetLabel(settings.defaultPreset),
                    onClick = { dialog = SettingsDialog.Preset },
                )
                SettingRow(
                    icon = Icons.Filled.AspectRatio,
                    title = stringResource(R.string.prefs_default_aspect),
                    value = aspectLabel(settings.defaultAspect),
                    onClick = { dialog = SettingsDialog.Aspect },
                )
                SettingRow(
                    icon = Icons.Filled.HighQuality,
                    title = stringResource(R.string.prefs_default_resolution),
                    value = resolutionLabel(settings.defaultResolution),
                    onClick = { dialog = SettingsDialog.Resolution },
                )
                SettingRow(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.prefs_default_fps),
                    value = fpsLabel(settings.defaultFps),
                    onClick = { dialog = SettingsDialog.Fps },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.Route,
                    title = stringResource(R.string.prefs_default_route_visible),
                    checked = settings.defaultRouteVisible,
                    onCheckedChange = { settingsVm.update { setDefaultRouteVisible(it) } },
                )
                SettingRow(
                    icon = Icons.Filled.LocationOn,
                    title = stringResource(R.string.prefs_default_marker_style),
                    value = markerStyleLabel(settings.defaultMarkerStyle),
                    onClick = { dialog = SettingsDialog.MarkerStyle },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.LocationOn,
                    title = stringResource(R.string.prefs_default_start_end),
                    checked = settings.defaultStartEndMarkers,
                    onCheckedChange = { settingsVm.update { setDefaultStartEndMarkers(it) } },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.Subtitles,
                    title = stringResource(R.string.prefs_default_intro_outro),
                    subtitle = stringResource(R.string.prefs_default_intro_outro_sub),
                    checked = settings.defaultIntroOutro,
                    onCheckedChange = { settingsVm.update { setDefaultIntroOutro(it) } },
                )
            }

            // -- Playback -------------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_playback))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.prefs_default_speed),
                    value = speedLabel(settings.defaultPlaybackSpeed),
                    onClick = { dialog = SettingsDialog.PlaybackSpeed },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.Videocam,
                    title = stringResource(R.string.prefs_follow_camera_default),
                    subtitle = stringResource(R.string.prefs_follow_camera_default_sub),
                    checked = settings.followCameraByDefault,
                    onCheckedChange = {
                        settingsVm.update {
                            setFollowCameraByDefault(it)
                            // The follow-camera default and the camera-mode
                            // default are one real setting: follow → track
                            // the marker, off → fixed overview.
                            setMapCameraMode(
                                if (it) CompositionCameraMode.FOLLOW_JOURNEY
                                else CompositionCameraMode.FIXED_OVERVIEW,
                            )
                        }
                    },
                )
                SwitchSettingRow(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.prefs_autoplay_preview),
                    subtitle = stringResource(R.string.prefs_autoplay_preview_sub),
                    checked = settings.autoplayPreview,
                    onCheckedChange = { settingsVm.update { setAutoplayPreview(it) } },
                )
            }

            // -- Storage ---------------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_storage))
            StorageSection(
                state = storageState,
                onRefresh = { settingsVm.refreshStorage() },
                onClearCache = { confirmClearCache = true },
            )

            // -- Privacy & about --------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_privacy_about))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.prefs_privacy_screen),
                    onClick = onPrivacy,
                )
                SettingRow(
                    icon = Icons.Filled.Article,
                    title = stringResource(R.string.prefs_licenses),
                    onClick = onLicenses,
                )
                SettingRow(
                    icon = Icons.Filled.Help,
                    title = stringResource(R.string.prefs_help),
                    onClick = onHelp,
                )
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.prefs_about),
                    onClick = onAbout,
                )
            }

            // -- Danger zone -------------------------------------------------------
            SectionHeader(title = stringResource(R.string.prefs_reset))
            Card(
                shape = RoundedCornerShape(CardRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingRow(
                    icon = Icons.Filled.RestartAlt,
                    title = stringResource(R.string.prefs_reset),
                    onClick = { confirmReset = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // -- dialogs ---------------------------------------------------------------
    dialog?.let { d ->
        SettingsChoiceDialog(
            dialog = d,
            settings = settings,
            supports4k = supports4k,
            onPick = { picked ->
                settingsVm.update { applySettingsPick(d, picked) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }

    if (confirmClearCache) {
        ConfirmDialog(
            title = stringResource(R.string.storage_clear_cache_title),
            text = stringResource(R.string.storage_clear_cache_text),
            confirmLabel = stringResource(R.string.storage_clear_cache),
            onConfirm = {
                confirmClearCache = false
                settingsVm.clearCache()
            },
            onDismiss = { confirmClearCache = false },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.prefs_reset_title),
            text = stringResource(R.string.prefs_reset_text),
            confirmLabel = stringResource(R.string.prefs_reset_confirm),
            onConfirm = {
                confirmReset = false
                settingsVm.resetSettings()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

private enum class SettingsDialog {
    Theme, CameraMode, Preset, Aspect, Resolution, Fps, MarkerStyle, PlaybackSpeed,
}

private suspend fun com.journeyvisualizer.app.data.SettingsRepository.applySettingsPick(
    dialog: SettingsDialog,
    picked: Int,
) {
    when (dialog) {
        SettingsDialog.Theme -> setTheme(AppTheme.entries[picked])
        SettingsDialog.CameraMode -> setMapCameraMode(CompositionCameraMode.entries[picked])
        SettingsDialog.Preset -> setDefaultPreset(DefaultPreset.entries[picked])
        SettingsDialog.Aspect -> setDefaultAspect(VideoAspectRatio.entries[picked])
        SettingsDialog.Resolution -> setDefaultResolution(AppSettings.RESOLUTIONS[picked])
        SettingsDialog.Fps -> setDefaultFps(AppSettings.FRAME_RATES[picked])
        SettingsDialog.MarkerStyle -> setDefaultMarkerStyle(VideoMarkerStyle.entries[picked])
        SettingsDialog.PlaybackSpeed -> setDefaultPlaybackSpeed(AppSettings.PLAYBACK_SPEEDS[picked])
    }
}

@Composable
private fun SettingsChoiceDialog(
    dialog: SettingsDialog,
    settings: AppSettings,
    supports4k: Boolean?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val notSupported = stringResource(R.string.res_4k_unsupported)
    val (title, options, selected) = when (dialog) {
        SettingsDialog.Theme -> Triple(
            stringResource(R.string.prefs_theme),
            AppTheme.entries.map { ChoiceOption(themeLabel(it)) },
            AppTheme.entries.indexOf(settings.theme),
        )
        SettingsDialog.CameraMode -> Triple(
            stringResource(R.string.prefs_map_camera),
            CompositionCameraMode.entries.map { ChoiceOption(cameraModeLabel(it)) },
            CompositionCameraMode.entries.indexOf(settings.mapCameraMode),
        )
        SettingsDialog.Preset -> Triple(
            stringResource(R.string.prefs_default_preset),
            DefaultPreset.entries.map { ChoiceOption(presetLabel(it)) },
            DefaultPreset.entries.indexOf(settings.defaultPreset),
        )
        SettingsDialog.Aspect -> Triple(
            stringResource(R.string.prefs_default_aspect),
            VideoAspectRatio.entries.map { ChoiceOption(aspectLabel(it)) },
            VideoAspectRatio.entries.indexOf(settings.defaultAspect),
        )
        SettingsDialog.Resolution -> Triple(
            stringResource(R.string.prefs_default_resolution),
            AppSettings.RESOLUTIONS.map { res ->
                val is4k = res == VideoResolutionPreset.P2160
                ChoiceOption(
                    label = resolutionLabel(res),
                    sublabel = if (is4k && supports4k == false) notSupported else null,
                    enabled = !is4k || supports4k != false,
                )
            },
            AppSettings.RESOLUTIONS.indexOf(settings.defaultResolution),
        )
        SettingsDialog.Fps -> Triple(
            stringResource(R.string.prefs_default_fps),
            AppSettings.FRAME_RATES.map { ChoiceOption(fpsLabel(it)) },
            AppSettings.FRAME_RATES.indexOf(settings.defaultFps),
        )
        SettingsDialog.MarkerStyle -> Triple(
            stringResource(R.string.prefs_default_marker_style),
            VideoMarkerStyle.entries.map { ChoiceOption(markerStyleLabel(it)) },
            VideoMarkerStyle.entries.indexOf(settings.defaultMarkerStyle),
        )
        SettingsDialog.PlaybackSpeed -> Triple(
            stringResource(R.string.prefs_default_speed),
            AppSettings.PLAYBACK_SPEEDS.map { ChoiceOption(speedLabel(it)) },
            AppSettings.PLAYBACK_SPEEDS.indexOf(settings.defaultPlaybackSpeed).coerceAtLeast(0),
        )
    }
    SingleChoiceDialog(
        title = title,
        options = options,
        selectedIndex = selected,
        onSelect = onPick,
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------------------
// Storage section.
// ---------------------------------------------------------------------------

@Composable
private fun StorageSection(
    state: SettingsViewModel.StorageState,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is SettingsViewModel.StorageState.Loading -> {
                    Text(
                        text = stringResource(R.string.storage_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is SettingsViewModel.StorageState.Error -> {
                    Text(
                        text = stringResource(R.string.storage_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is SettingsViewModel.StorageState.Ready -> {
                    val info = state.info
                    val videosValue = stringResource(
                        R.string.storage_videos_count,
                        info.videoCount,
                    ) + " · " + StorageOwnership.formatBytes(info.videosBytes)
                    StorageStatRow(
                        icon = Icons.Filled.Videocam,
                        label = stringResource(R.string.storage_videos),
                        value = videosValue,
                    )
                    Spacer(Modifier.height(8.dp))
                    StorageStatRow(
                        icon = Icons.Filled.Storage,
                        label = stringResource(R.string.storage_cache),
                        value = StorageOwnership.formatBytes(info.cacheBytes),
                    )
                    info.cacheBreakdown.forEach { (dir, bytes) ->
                        Text(
                            text = "· ${cacheDirLabel(dir)} — ${StorageOwnership.formatBytes(bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 38.dp, top = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.storage_refresh))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearCache) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.storage_clear_cache))
                }
            }
        }
    }
}

@Composable
private fun StorageStatRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Privacy screen (Phase 9).
// ---------------------------------------------------------------------------

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.privacy_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            PrivacySection(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.privacy_local_title),
                text = stringResource(R.string.privacy_local_text),
            )
            PrivacySection(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.privacy_timeline_title),
                text = stringResource(R.string.privacy_timeline_text),
            )
            PrivacySection(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.privacy_location_title),
                text = stringResource(R.string.privacy_location_text),
            )
            PrivacySection(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.privacy_geonames_title),
                text = stringResource(R.string.privacy_geonames_text),
            )
            PrivacySection(
                icon = Icons.Filled.Map,
                title = stringResource(R.string.privacy_network_title),
                text = stringResource(R.string.privacy_network_text),
            )
            PrivacySection(
                icon = Icons.Filled.Videocam,
                title = stringResource(R.string.privacy_videos_title),
                text = stringResource(R.string.privacy_videos_text),
            )
            PrivacySection(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.privacy_analytics_title),
                text = stringResource(R.string.privacy_analytics_text),
            )
            PrivacySection(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.privacy_cloud_title),
                text = stringResource(R.string.privacy_cloud_text),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PrivacySection(icon: ImageVector, title: String, text: String) {
    Card(
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Open-source licenses screen (Phase 9).
// ---------------------------------------------------------------------------

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.licenses_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LicenseRow(
                name = stringResource(R.string.license_kotlin),
                license = stringResource(R.string.license_apache),
            )
            LicenseRow(
                name = stringResource(R.string.license_compose),
                license = stringResource(R.string.license_apache),
            )
            LicenseRow(
                name = stringResource(R.string.license_osmdroid),
                license = stringResource(R.string.license_apache),
            )
            LicenseRow(
                name = stringResource(R.string.license_geonames),
                license = stringResource(R.string.license_geonames_detail),
            )
            LicenseRow(
                name = stringResource(R.string.license_tiles),
                license = stringResource(R.string.license_tiles_detail),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LicenseRow(name: String, license: String) {
    Card(
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Help & Support (Phase 9 rewrite — answers match the real implementation).
// ---------------------------------------------------------------------------

@Composable
fun HelpScreen(onBack: () -> Unit) {
    var expanded by remember { mutableStateOf<Int?>(null) }
    val faqs = listOf(
        R.string.help_q_import to R.string.help_a_import,
        R.string.help_q_formats to R.string.help_a_formats,
        R.string.help_q_noname to R.string.help_a_noname,
        R.string.help_q_style to R.string.help_a_style,
        R.string.help_q_render to R.string.help_a_render,
        R.string.help_q_where to R.string.help_a_where,
        R.string.help_q_rename to R.string.help_a_rename,
        R.string.help_q_delete to R.string.help_a_delete,
        R.string.help_q_missing to R.string.help_a_missing,
        R.string.help_q_upload to R.string.help_a_upload,
        R.string.help_q_tracking to R.string.help_a_tracking,
    )

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.help_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            faqs.forEachIndexed { index, (q, a) ->
                val open = expanded == index
                Card(
                    shape = RoundedCornerShape(CardRadius),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = {
                                expanded = if (open) null else index
                            })
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(q),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.Help,
                                contentDescription = if (open) "Collapse answer" else "Expand answer",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (open) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(a),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// About (Phase 9 improvements).
// ---------------------------------------------------------------------------

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
) {
    val versionName = remember { BuildConfig.VERSION_NAME }

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.about_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            AppLogo(size = 88.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_version_arg, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_desc_p9),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(CardRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.about_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.prefs_privacy_screen),
                    onClick = onPrivacy,
                )
                SettingRow(
                    icon = Icons.Filled.Article,
                    title = stringResource(R.string.prefs_licenses),
                    onClick = onLicenses,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

