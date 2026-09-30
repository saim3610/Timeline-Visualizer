package com.journeyvisualizer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.journeyvisualizer.app.data.SettingsRepository
import com.journeyvisualizer.app.settings.AppTheme
import com.journeyvisualizer.app.ui.navigation.AppNav
import com.journeyvisualizer.app.ui.theme.JourneyVisualizerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Export completion notifications can deep-link straight to the videos tab.
        val startTab = intent.getStringExtra("tab") ?: "create"
        // Phase 9: the theme is a persisted user setting (system/light/dark).
        val settingsRepo = SettingsRepository(applicationContext)
        setContent {
            val theme by remember { settingsRepo.theme }.collectAsState(initial = AppTheme.SYSTEM)
            val darkTheme = when (theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            JourneyVisualizerTheme(darkTheme = darkTheme) {
                AppNav(startTab = startTab)
            }
        }
    }
}
