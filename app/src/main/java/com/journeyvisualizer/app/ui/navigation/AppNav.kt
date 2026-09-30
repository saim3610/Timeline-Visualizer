package com.journeyvisualizer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.navigation.Phase1Nav

/**
 * Application navigation entry point (Phase 1).
 *
 * The Phase 1 UI/UX foundation lives under `ui.phase1` (navigation, screens,
 * import pipeline, map, animation, export, history, settings). The pre-Phase-1
 * screens under `ui.screens` are preserved untouched.
 */
@Composable
fun AppNav(startTab: String = "create") {
    val ux: Phase1ViewModel = viewModel()
    Phase1Nav(ux = ux, startTab = startTab)
}
