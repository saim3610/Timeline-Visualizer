package com.journeyvisualizer.app.ui.phase1.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.export.ExportProgressBus
import com.journeyvisualizer.app.export.RenderState
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.PrimaryButton
import com.journeyvisualizer.app.ui.phase1.components.VideoPlayer
import com.journeyvisualizer.app.ui.util.formatDurationMs
import com.journeyvisualizer.app.ui.util.formatFileSize

/**
 * Phase 7 export result: playable video plus the render's real metadata.
 *
 * Reads the completed render from [ExportProgressBus]; playback uses the
 * shared [VideoPlayer] surface. Basic sharing goes through a plain
 * ACTION_SEND intent (no new framework). The "Details" button opens the
 * Phase 8 history detail for the new video when registration succeeded.
 */
@Composable
fun VideoExportResultScreen(
    onBack: () -> Unit,
    onOpenDetails: (historyId: String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val completed = remember { ExportProgressBus.lastCompleted }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            JVTopBar(
                title = stringResource(R.string.export_result_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (completed == null) {
                Text(stringResource(R.string.export_result_missing))
                return@Column
            }
            val c: RenderState.Completed = completed

            VideoPlayer(
                uri = c.uri,
                autoPlay = true,
                modifier = Modifier.aspectRatio(
                    c.width.toFloat() / c.height.toFloat().coerceAtLeast(1f),
                ),
            )

            ResultRow(
                label = stringResource(R.string.export_result_file),
                value = c.fileName,
            )
            ResultRow(
                label = stringResource(R.string.export_result_duration),
                value = formatDurationMs(c.durationMs),
            )
            ResultRow(
                label = stringResource(R.string.export_result_resolution),
                value = "${c.width}×${c.height} · ${c.fps} fps",
            )
            ResultRow(
                label = stringResource(R.string.export_result_size),
                value = formatFileSize(c.sizeBytes),
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { shareVideo(context, c.uri, snackbar, scope) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.export_result_share))
                }
                val historyId = c.historyId
                if (historyId != null) {
                    PrimaryButton(
                        text = stringResource(R.string.export_result_details),
                        onClick = { onOpenDetails(historyId) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.export_result_history),
                        onClick = onOpenHistory,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
