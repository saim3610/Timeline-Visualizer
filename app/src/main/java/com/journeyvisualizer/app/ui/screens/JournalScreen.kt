package com.journeyvisualizer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.JourneyViewModel
import com.journeyvisualizer.app.ui.util.formatDate
import com.journeyvisualizer.app.ui.util.formatDateTime
import com.journeyvisualizer.app.ui.util.formatDistance
import kotlinx.coroutines.launch

/**
 * The Travel Journal: every imported Timeline.json is listed here.
 * Select one or more trips and turn them into a single video — overlapping
 * exports are merged and duplicate points dropped automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(viewModel: JourneyViewModel, onMakeVideo: () -> Unit) {
    val entries by viewModel.journalEntries.collectAsState()
    val unit by viewModel.settings.distanceUnit.collectAsState(initial = "km")
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val combineFailedMsg = stringResource(R.string.journal_combine_failed)
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }

    fun makeVideo() {
        if (selected.isEmpty() || busy) return
        busy = true
        viewModel.loadJournalAsDraft(selected.toList()) { ok ->
            busy = false
            if (ok) {
                selected = emptySet()
                onMakeVideo()
            } else {
                scope.launch { snackbar.showSnackbar(combineFailedMsg) }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.journal_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Button(
                    onClick = { makeVideo() },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.journal_make_video))
                }
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.journal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.journal_combine_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    val isSelected = entry.id in selected
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(4.dp),
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + entry.id
                                    else selected - entry.id
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${formatDate(entry.startMs)} – ${formatDate(entry.endMs)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${formatDistance(entry.distanceM, unit)} • " +
                                        stringResource(R.string.create_points, entry.pointCount) +
                                        " • " + stringResource(
                                            R.string.journal_imported_on,
                                            formatDateTime(entry.importedAtMs)
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteJournalEntry(entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
