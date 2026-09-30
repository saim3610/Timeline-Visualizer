package com.journeyvisualizer.app.ui.phase1.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.history.HistorySort
import com.journeyvisualizer.app.history.VideoHistoryItem
import com.journeyvisualizer.app.ui.phase1.HistoryMessage
import com.journeyvisualizer.app.ui.phase1.HistoryUiState
import com.journeyvisualizer.app.ui.phase1.HistoryViewModel
import com.journeyvisualizer.app.ui.phase1.components.EmptyState
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.util.formatDurationMs
import com.journeyvisualizer.app.ui.util.formatFileSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Phase 8 — the real My Videos screen (replaces the Phase 1 placeholder).
 *
 * Lists locally generated videos newest-first, with local search, sort,
 * thumbnails, and per-card actions. All data comes from the Room-backed
 * [HistoryViewModel]; the UI never touches the database or MediaStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyVideosScreen(
    onCreateVideo: () -> Unit,
    onOpenVideo: (videoId: String) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsState()
    val sort by vm.sort.collectAsState()
    val query by vm.query.collectAsState()
    val recentOnly by vm.recentOnly.collectAsState()
    val deletingIds by vm.deletingIds.collectAsState()
    val thumbs by vm.thumbs.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var renameTarget by remember { mutableStateOf<VideoHistoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<VideoHistoryItem?>(null) }

    LaunchedEffect(message) {
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
            JVTopBar(title = stringResource(R.string.history_title))
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is HistoryUiState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is HistoryUiState.Error -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.message,
                        modifier = Modifier.padding(32.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is HistoryUiState.Empty -> {
                    if (query.isBlank() && !recentOnly) {
                        EmptyState(
                            icon = Icons.Filled.Movie,
                            title = stringResource(R.string.history_empty_title),
                            text = stringResource(R.string.history_empty_text),
                            actionLabel = stringResource(R.string.history_upload),
                            onAction = onCreateVideo,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        EmptySearchState(
                            onClear = {
                                vm.setQuery("")
                                vm.setRecentOnly(false)
                            },
                        )
                    }
                }

                is HistoryUiState.Loaded -> {
                    HistoryToolbar(
                        query = query,
                        onQuery = vm::setQuery,
                        sort = sort,
                        onSort = vm::setSort,
                        recentOnly = recentOnly,
                        onRecentOnly = vm::setRecentOnly,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.videos, key = { it.id }) { item ->
                            VideoCard(
                                item = item,
                                thumb = thumbs[item.id],
                                isDeleting = deletingIds.contains(item.id),
                                onLoadThumb = { vm.thumbnailFor(item) },
                                onOpen = { onOpenVideo(item.id) },
                                onRename = { renameTarget = item },
                                onDelete = { deleteTarget = item },
                                onShare = {
                                    shareVideo(context, Uri.parse(item.contentUri), snackbar, scope)
                                },
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }

    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.displayName,
            onConfirm = { name ->
                vm.rename(item.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { item ->
        DeleteConfirmDialog(
            name = item.displayName,
            onConfirm = {
                vm.delete(item.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

fun shareVideo(
    context: android.content.Context,
    uri: Uri,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(share, context.getString(R.string.export_result_share)),
        )
    } catch (_: ActivityNotFoundException) {
        scope.launch { snackbar.showSnackbar(context.getString(R.string.share_no_app)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryToolbar(
    query: String,
    onQuery: (String) -> Unit,
    sort: HistorySort,
    onSort: (HistorySort) -> Unit,
    recentOnly: Boolean,
    onRecentOnly: (Boolean) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.history_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Text(sortLabel(sort))
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_sort_newest)) },
                        onClick = { onSort(HistorySort.NEWEST_FIRST); sortOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_sort_oldest)) },
                        onClick = { onSort(HistorySort.OLDEST_FIRST); sortOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_sort_name)) },
                        onClick = { onSort(HistorySort.NAME_AZ); sortOpen = false },
                    )
                }
            }
            FilterChip(
                selected = recentOnly,
                onClick = { onRecentOnly(!recentOnly) },
                label = { Text(stringResource(R.string.history_filter_recent)) },
            )
        }
    }
}

@Composable
private fun sortLabel(sort: HistorySort): String = stringResource(
    when (sort) {
        HistorySort.NEWEST_FIRST -> R.string.history_sort_newest
        HistorySort.OLDEST_FIRST -> R.string.history_sort_oldest
        HistorySort.NAME_AZ -> R.string.history_sort_name
    },
)

@Composable
private fun EmptySearchState(onClear: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Search,
        title = stringResource(R.string.history_search_empty_title),
        text = stringResource(R.string.history_search_empty_text),
        actionLabel = stringResource(R.string.history_search_clear),
        onAction = onClear,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun VideoCard(
    item: VideoHistoryItem,
    thumb: android.graphics.Bitmap?,
    isDeleting: Boolean,
    onLoadThumb: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(item.id, item.thumbnailPath) { onLoadThumb() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = !isDeleting,
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.history_card_options),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_play)) },
                                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                onClick = { menuOpen = false; onOpen() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_share)) },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menuOpen = false; onShare() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_rename)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { menuOpen = false; onRename() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_delete)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
                item.routeLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatDate(item.createdAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${item.resolutionLabel} · ${formatDurationMs(item.durationMs)} · " +
                        formatFileSize(item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
