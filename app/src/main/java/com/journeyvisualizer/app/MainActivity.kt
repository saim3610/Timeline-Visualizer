package com.journeyvisualizer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // A crash in the previous session leaves a report for the user to share.
        val pendingCrash = CrashReporter.consumeReport(applicationContext)
        setContent {
            val theme by remember { settingsRepo.theme }.collectAsState(initial = AppTheme.SYSTEM)
            val darkTheme = when (theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            var showCrash by remember { mutableStateOf(pendingCrash != null) }
            JourneyVisualizerTheme(darkTheme = darkTheme) {
                AppNav(startTab = startTab)
                if (showCrash && pendingCrash != null) {
                    CrashReportDialog(
                        report = pendingCrash,
                        onDismiss = { showCrash = false },
                    )
                }
            }
        }
    }
}

/** Shows the previous session's crash report with a one-tap copy. */
@Composable
private fun CrashReportDialog(
    report: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_body))
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = report.take(4_000),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("crash-report", report)
                    )
                    copied = true
                }
            ) {
                Text(
                    stringResource(
                        if (copied) R.string.crash_copied else R.string.crash_copy
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_dismiss))
            }
        },
    )
}
