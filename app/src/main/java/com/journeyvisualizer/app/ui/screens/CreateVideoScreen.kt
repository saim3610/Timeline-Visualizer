package com.journeyvisualizer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.CAMERA_LABELS
import com.journeyvisualizer.app.data.JourneyViewModel
import com.journeyvisualizer.app.data.TIME_WARP_LABELS
import com.journeyvisualizer.app.data.VIDEO_FORMATS
import com.journeyvisualizer.app.data.VIDEO_FORMAT_LABELS
import com.journeyvisualizer.app.data.geo.VisitedCities
import com.journeyvisualizer.app.export.ExportConfig
import com.journeyvisualizer.app.export.ExportService
import com.journeyvisualizer.app.map.CameraMode
import com.journeyvisualizer.app.map.JourneyEngine
import com.journeyvisualizer.app.map.TimeWarp
import com.journeyvisualizer.app.ui.components.OptionDropdown
import com.journeyvisualizer.app.ui.util.formatDate
import com.journeyvisualizer.app.ui.util.formatDistance
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVideoScreen(viewModel: JourneyViewModel, onPreview: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val importState by viewModel.importState.collectAsState()
    val draft = viewModel.draft
    val revision = viewModel.draftRevision

    // Editable copies, reset whenever a new file is imported.
    var title by remember(revision) { mutableStateOf(draft.title) }
    var startMs by remember(revision) { mutableStateOf(draft.startMs ?: 0L) }
    var endMs by remember(revision) { mutableStateOf(draft.endMs ?: 0L) }
    var durationSec by remember(revision) { mutableIntStateOf(draft.durationSec) }
    var formatKey by remember(revision) { mutableStateOf(draft.formatKey) }
    var cameraKey by remember(revision) { mutableStateOf(draft.cameraKey) }
    var warpKey by remember(revision) { mutableStateOf(draft.timeWarpKey) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var prefetchProgress by remember { mutableFloatStateOf(-1f) }
    var prefetchDone by remember(revision) { mutableStateOf(false) }

    val unit by viewModel.settings.distanceUnit.collectAsState(initial = "km")
    val stops by viewModel.visitedCities.collectAsState()
    val exportStartedMsg = stringResource(R.string.create_export_started)
    val noDataMsg = stringResource(R.string.create_no_data)
    val prefetchDoneMsg = stringResource(R.string.create_prefetch_done)
    val prefetchFailedMsg = stringResource(R.string.create_prefetch_failed)

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importTimeline(uri)
    }

    fun syncDraft() {
        draft.title = title
        draft.startMs = startMs
        draft.endMs = endMs
        draft.durationSec = durationSec
        draft.formatKey = formatKey
        draft.cameraKey = cameraKey
        draft.timeWarpKey = warpKey
    }

    fun doExport() {
        val loaded = importState as? JourneyViewModel.ImportState.Loaded ?: return
        val ranged = JourneyEngine(loaded.journey)
            .filterRange(startMs, endMs)
            .copy(title = title.ifBlank { "My Journey" })
        if (ranged.points.size < 2) {
            scope.launch { snackbar.showSnackbar(noDataMsg) }
            return
        }
        val (w, h) = VIDEO_FORMATS[formatKey] ?: (1080 to 1080)
        val config = ExportConfig(
            width = w,
            height = h,
            durationSec = durationSec,
            cameraMode = CameraMode.valueOf(cameraKey),
            timeWarp = TimeWarp.valueOf(warpKey),
            title = ranged.title,
            cities = stops,
        )
        syncDraft()
        scope.launch {
            viewModel.settings.setDefaultDurationSec(durationSec)
            viewModel.settings.setVideoFormat(formatKey)
            viewModel.settings.setCameraMode(cameraKey)
            viewModel.settings.setDefaultTimeWarp(warpKey)
        }
        ExportService.enqueue(context, ranged, config)
        scope.launch { snackbar.showSnackbar(exportStartedMsg) }
    }

    fun doPrefetch() {
        if (prefetchProgress >= 0f) return
        syncDraft()
        prefetchProgress = 0f
        prefetchDone = false
        viewModel.prefetchDraftTiles(
            onProgress = { prefetchProgress = it },
            onDone = { ok ->
                prefetchProgress = -1f
                prefetchDone = ok
                scope.launch {
                    snackbar.showSnackbar(if (ok) prefetchDoneMsg else prefetchFailedMsg)
                }
            }
        )
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { doExport() }

    fun onExportClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doExport()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.create_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Import card ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (val s = importState) {
                        is JourneyViewModel.ImportState.Idle -> {
                            Text(
                                stringResource(R.string.create_intro),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.create_howto),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { pickFile.launch(arrayOf("application/json")) }) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.UploadFile, contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.create_pick_file))
                            }
                        }
                        is JourneyViewModel.ImportState.Loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.create_importing))
                            }
                        }
                        is JourneyViewModel.ImportState.Error -> {
                            Text(
                                stringResource(R.string.create_no_data),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { pickFile.launch(arrayOf("application/json")) }) {
                                Text(stringResource(R.string.create_pick_file))
                            }
                        }
                        is JourneyViewModel.ImportState.Loaded -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Description, contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(s.fileName, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                stringResource(R.string.create_points, s.journey.points.size) +
                                    " • " + formatDistance(s.journey.totalDistanceM, unit) + "\n" +
                                    "${formatDate(s.journey.startMs)} → ${formatDate(s.journey.endMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            s.warnings.forEach { w ->
                                Text(
                                    w,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            Text(
                                stringResource(R.string.create_journal_saved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            TextButton(onClick = {
                                viewModel.clearImport()
                                pickFile.launch(arrayOf("application/json"))
                            }) {
                                Text(stringResource(R.string.create_pick_file))
                            }
                        }
                    }
                }
            }

            // ---- Configuration ----
            if (importState is JourneyViewModel.ImportState.Loaded) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showStartPicker = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "${stringResource(R.string.create_start_date)}\n${formatDate(startMs)}",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            OutlinedButton(
                                onClick = { showEndPicker = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "${stringResource(R.string.create_end_date)}\n${formatDate(endMs)}",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.create_video_title)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (stops.isNotEmpty()) {
                            Text(
                                stringResource(R.string.create_visited_cities),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                stops.forEach { stop ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(stop.city.name) },
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { title = VisitedCities.titleFor(stops) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.create_use_cities_title))
                            }
                        }

                        Text(
                            stringResource(R.string.create_duration, durationSec),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = durationSec.toFloat(),
                            onValueChange = { durationSec = it.toInt() },
                            valueRange = 10f..300f,
                            steps = 28,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OptionDropdown(
                            label = stringResource(R.string.create_format),
                            options = VIDEO_FORMATS.keys.toList(),
                            selected = formatKey,
                            labelOf = { stringResource(VIDEO_FORMAT_LABELS[it] ?: R.string.fmt_square_1080) },
                            onSelect = { formatKey = it },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OptionDropdown(
                            label = stringResource(R.string.create_camera),
                            options = CAMERA_LABELS.keys.toList(),
                            selected = cameraKey,
                            labelOf = { stringResource(CAMERA_LABELS[it] ?: R.string.camera_steady) },
                            onSelect = { cameraKey = it },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OptionDropdown(
                            label = stringResource(R.string.create_pace),
                            options = TIME_WARP_LABELS.keys.toList(),
                            selected = warpKey,
                            labelOf = { stringResource(TIME_WARP_LABELS[it] ?: R.string.pace_linear) },
                            onSelect = { warpKey = it },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Offline tile pack for the current draft.
                        if (prefetchProgress >= 0f) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { prefetchProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(
                                        R.string.create_prefetching,
                                        (prefetchProgress * 100).toInt()
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { doPrefetch() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Download, contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.create_prefetch))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { syncDraft(); onPreview() },
                                modifier = Modifier.weight(1f),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.PlayArrow, contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.create_preview))
                            }
                            Button(
                                onClick = { onExportClicked() },
                                modifier = Modifier.weight(1f),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Movie, contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.create_export))
                            }
                        }
                        Text(
                            stringResource(R.string.create_export_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showStartPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = startMs)
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { startMs = it }
                    showStartPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) { DatePicker(state = state) }
    }
    if (showEndPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = endMs)
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        // End of the selected day.
                        endMs = it + 86_399_999L
                    }
                    showEndPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) { DatePicker(state = state) }
    }

    // Keep the draft title in sync after the template is applied on import.
    LaunchedEffect(revision) {
        title = draft.title
        startMs = draft.startMs ?: startMs
        endMs = draft.endMs ?: endMs
    }
}
