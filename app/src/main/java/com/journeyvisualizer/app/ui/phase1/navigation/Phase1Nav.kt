package com.journeyvisualizer.app.ui.phase1.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.SettingsViewModel
import com.journeyvisualizer.app.ui.phase1.components.AppLogo
import com.journeyvisualizer.app.ui.phase1.screens.AboutScreen
import com.journeyvisualizer.app.ui.phase1.screens.FileSummaryScreen
import com.journeyvisualizer.app.ui.phase1.screens.HelpScreen
import com.journeyvisualizer.app.ui.phase1.screens.HomeScreen
import com.journeyvisualizer.app.ui.phase1.screens.LicensesScreen
import com.journeyvisualizer.app.ui.phase1.screens.MapStyleScreen
import com.journeyvisualizer.app.ui.phase1.screens.MyVideosScreen
import com.journeyvisualizer.app.ui.phase1.screens.PrivacyScreen
import com.journeyvisualizer.app.ui.phase1.screens.ProcessingScreen
import com.journeyvisualizer.app.ui.phase1.screens.SettingsScreen
import com.journeyvisualizer.app.ui.phase1.screens.SplashScreen
import com.journeyvisualizer.app.ui.phase1.screens.TimelinePreviewScreen
import com.journeyvisualizer.app.ui.phase1.screens.UploadScreen
import com.journeyvisualizer.app.ui.phase1.screens.VideoPreviewScreen
import com.journeyvisualizer.app.ui.phase1.screens.VideoExportResultScreen
import com.journeyvisualizer.app.ui.phase1.screens.VideoDetailScreen
import kotlinx.coroutines.launch

object Phase1Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val UPLOAD = "upload"
    const val FILE_SUMMARY = "file_summary"
    const val PROCESSING = "processing"
    const val TIMELINE_PREVIEW = "timeline_preview"
    const val MAP_STYLE = "map_style"
    const val VIDEO_PREVIEW = "video_preview"
    const val EXPORT_RESULT = "export_result"
    const val VIDEO_DETAIL = "video_detail/{videoId}"
    const val HELP = "help"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val LICENSES = "licenses"
}

private data class BottomTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab(Phase1Routes.HOME, R.string.tab_home, Icons.Filled.Home),
    BottomTab(Phase1Routes.HISTORY, R.string.tab_history, Icons.Filled.History),
    BottomTab(Phase1Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

private data class DrawerEntry(val route: String, val label: String, val icon: ImageVector)

/**
 * Phase 1 navigation: splash → tabbed main flow (Home / History / Settings)
 * with a full-screen import → preview → customize → generate flow on top.
 *
 * Legacy deep-link tab values are mapped forward: the export service still
 * fires `tab=videos`, which now lands on History.
 */
@Composable
fun Phase1Nav(ux: Phase1ViewModel, startTab: String = "home") {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var splashDone by rememberSaveable { mutableStateOf(false) }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = splashDone && currentRoute in BOTTOM_TABS.map { it.route }

    val landing = when (startTab) {
        "videos", Phase1Routes.HISTORY -> Phase1Routes.HISTORY
        Phase1Routes.SETTINGS, "settings" -> Phase1Routes.SETTINGS
        else -> Phase1Routes.HOME
    }

    fun goTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun go(route: String) {
        navController.navigate(route)
    }

    fun back() {
        navController.popBackStack()
    }

    val drawerEntries = listOf(
        DrawerEntry(Phase1Routes.HOME, stringResource(R.string.tab_home), Icons.Filled.Home),
        DrawerEntry(Phase1Routes.HISTORY, stringResource(R.string.tab_history), Icons.Filled.History),
        DrawerEntry(Phase1Routes.SETTINGS, stringResource(R.string.tab_settings), Icons.Filled.Settings),
        DrawerEntry(Phase1Routes.HELP, stringResource(R.string.help_title), Icons.Filled.Help),
        DrawerEntry(Phase1Routes.PRIVACY, stringResource(R.string.privacy_title), Icons.Filled.Shield),
        DrawerEntry(Phase1Routes.ABOUT, stringResource(R.string.about_title), Icons.Filled.Info),
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        AppLogo(size = 44.dp)
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    drawerEntries.forEach { entry ->
                        NavigationDrawerItem(
                            label = { Text(entry.label) },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            selected = currentRoute == entry.route,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (entry.route in BOTTOM_TABS.map { it.route }) goTab(entry.route)
                                else go(entry.route)
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        BOTTOM_TABS.forEach { tab ->
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { goTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Phase1Routes.SPLASH,
                modifier = Modifier.padding(padding),
            ) {
                composable(Phase1Routes.SPLASH) {
                    SplashScreen(onFinished = {
                        splashDone = true
                        navController.navigate(landing) {
                            popUpTo(Phase1Routes.SPLASH) { inclusive = true }
                        }
                    })
                }
                composable(Phase1Routes.HOME) {
                    HomeScreen(
                        onUpload = { go(Phase1Routes.UPLOAD) },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(Phase1Routes.HISTORY) {
                    // Phase 8: the real My Videos screen (metadata + URI refs;
                    // the legacy mock HistoryScreen was removed in Phase 9).
                    MyVideosScreen(
                        onCreateVideo = { go(Phase1Routes.UPLOAD) },
                        onOpenVideo = { id -> go("video_detail/$id") },
                    )
                }
                composable(Phase1Routes.SETTINGS) {
                    val settingsVm: SettingsViewModel = viewModel()
                    SettingsScreen(
                        settingsVm = settingsVm,
                        ux = ux,
                        onMapStyle = { go(Phase1Routes.MAP_STYLE) },
                        onPrivacy = { go(Phase1Routes.PRIVACY) },
                        onLicenses = { go(Phase1Routes.LICENSES) },
                        onHelp = { go(Phase1Routes.HELP) },
                        onAbout = { go(Phase1Routes.ABOUT) },
                    )
                }
                composable(Phase1Routes.UPLOAD) {
                    UploadScreen(
                        ux = ux,
                        onBack = ::back,
                        onFileReady = { go(Phase1Routes.PROCESSING) },
                    )
                }
                composable(Phase1Routes.PROCESSING) {
                    ProcessingScreen(
                        ux = ux,
                        onBack = {
                            ux.cancelImport()
                            back()
                        },
                        onFinished = {
                            navController.navigate(Phase1Routes.FILE_SUMMARY) {
                                popUpTo(Phase1Routes.PROCESSING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Phase1Routes.FILE_SUMMARY) {
                    FileSummaryScreen(
                        ux = ux,
                        onBack = {
                            ux.clearImport()
                            back()
                        },
                        onContinue = { go(Phase1Routes.TIMELINE_PREVIEW) },
                        onChooseAnother = {
                            ux.clearImport()
                            back()
                        },
                    )
                }
                composable(Phase1Routes.TIMELINE_PREVIEW) {
                    TimelinePreviewScreen(
                        ux = ux,
                        onBack = ::back,
                        onMapStyle = { go(Phase1Routes.MAP_STYLE) },
                        onVideoPreview = { go(Phase1Routes.VIDEO_PREVIEW) },
                    )
                }
                composable(Phase1Routes.MAP_STYLE) {
                    MapStyleScreen(ux = ux, onBack = ::back, onApplied = ::back)
                }
                composable(Phase1Routes.VIDEO_PREVIEW) {
                    VideoPreviewScreen(
                        ux = ux,
                        onBack = ::back,
                        onExportComplete = { go(Phase1Routes.EXPORT_RESULT) },
                    )
                }
                composable(Phase1Routes.EXPORT_RESULT) {
                    VideoExportResultScreen(
                        onBack = ::back,
                        onOpenDetails = { id -> go("video_detail/$id") },
                        onOpenHistory = { go(Phase1Routes.HISTORY) },
                    )
                }
                composable(Phase1Routes.VIDEO_DETAIL) { entry ->
                    val id = entry.arguments?.getString("videoId").orEmpty()
                    VideoDetailScreen(
                        videoId = id,
                        onBack = ::back,
                        onDeleted = { go(Phase1Routes.HISTORY) },
                    )
                }
                composable(Phase1Routes.HELP) { HelpScreen(onBack = ::back) }
                composable(Phase1Routes.ABOUT) {
                    AboutScreen(
                        onBack = ::back,
                        onPrivacy = { go(Phase1Routes.PRIVACY) },
                        onLicenses = { go(Phase1Routes.LICENSES) },
                    )
                }
                composable(Phase1Routes.PRIVACY) { PrivacyScreen(onBack = ::back) }
                composable(Phase1Routes.LICENSES) { LicensesScreen(onBack = ::back) }
            }
        }
    }
}
