package com.journeyvisualizer.app.ui.phase1.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.history.VideoHistoryItem
import com.journeyvisualizer.app.ui.phase1.HistoryMessage
import com.journeyvisualizer.app.ui.phase1.HistoryViewModel
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.VideoPlayer
import com.journeyvisualizer.app.ui.util.formatDurationMs
import com.journeyvisualizer.app.ui.util.formatFileSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Phase 8 video detail screen.
 *
 * Loads the record by stable [videoId] (never a whole object through
 * navigation) and shows the large player plus full metadata. Actions:
 * rename, delete (confirmed), share. Playback reuses the shared
 * [VideoPlayer] surface.
 */
@Composable
fun VideoDetailScreen(
    videoId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val item by vm.detailFlow(videoId).collectAsState(initial = null)
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        scope.launch {
            snackbar.showSnackbar(
                when (m) {
                    is HistoryMessage.Info -> m.text
                    is HistoryMessage.Error -> m.text
                },
            )
        }
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            JVTopBar(
                title = item?.displayName ?: stringResource(R.string.detail_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val video = item
        if (video == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VideoPlayer(
                uri = Uri.parse(video.contentUri),
                modifier = Modifier.aspectRatio(
                    video.width.toFloat() / video.height.toFloat().coerceAtLeast(1f),
                ),
            )

            Text(
                video.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            video.routeLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            DetailRow(
                stringResource(R.string.detail_created),
                formatDateTime(video.createdAtMs),
            )
            DetailRow(
                stringResource(R.string.detail_duration),
                formatDurationMs(video.durationMs),
            )
            DetailRow(
                stringResource(R.string.detail_resolution),
                "${video.width}×${video.height}",
            )
            DetailRow(stringResource(R.string.detail_fps), "${video.fps} fps")
            DetailRow(
                stringResource(R.string.detail_size),
                formatFileSize(video.sizeBytes),
            )
            DetailRow(
                stringResource(R.string.detail_aspect),
                video.aspectRatio,
            )
            video.mapStyle?.let {
                DetailRow(stringResource(R.string.detail_map_style), it)
            }
            timelineRangeLabel(video)?.let {
                DetailRow(stringResource(R.string.detail_timeline), it)
            }
            DetailRow(
                stringResource(R.string.detail_events),
                video.eventCount.toString(),
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        shareVideo(
                            context, Uri.parse(video.contentUri), snackbar, scope,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.history_action_share))
                }
                OutlinedButton(
                    onClick = { showRename = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Edit, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.history_action_rename))
                }
                OutlinedButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.Delete, null,
                        Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.history_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(
            currentName = item?.displayName ?: "",
            onConfirm = { name ->
                item?.let { vm.rename(it.id, name) }
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete) {
        DeleteConfirmDialog(
            name = item?.displayName ?: "",
            onConfirm = {
                // Phase 9: navigate away only after the file deletion is
                // confirmed. If deletion fails the record (and the error)
                // stay visible on this screen.
                item?.let { vm.delete(it.id) { deleted -> if (deleted) onDeleted() } }
                showDelete = false
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

/** "12 Mar 2026 – 18 Mar 2026", or null when the range is unknown. */
private fun timelineRangeLabel(item: VideoHistoryItem): String? {
    val start = item.timelineStartMs ?: return null
    val end = item.timelineEndMs ?: return null
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
}
