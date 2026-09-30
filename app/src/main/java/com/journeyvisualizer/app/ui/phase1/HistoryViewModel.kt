package com.journeyvisualizer.app.ui.phase1

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.journeyvisualizer.app.history.HistoryListLogic
import com.journeyvisualizer.app.history.HistoryRepository
import com.journeyvisualizer.app.history.HistorySort
import com.journeyvisualizer.app.history.RenameValidator
import com.journeyvisualizer.app.history.ThumbnailStore
import com.journeyvisualizer.app.history.VideoHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen-level state for My Videos. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data object Empty : HistoryUiState
    data class Loaded(val videos: List<VideoHistoryItem>) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}

/** One-shot UI messages (rename/delete/share feedback). */
sealed interface HistoryMessage {
    data class Info(val text: String) : HistoryMessage
    data class Error(val text: String) : HistoryMessage
}

/**
 * ViewModel for the Phase 8 My Videos screen.
 *
 * Owns the database observation, orphan refresh, sorting, local search,
 * rename/delete orchestration, and the in-memory thumbnail cache.
 * The UI never touches Room or MediaStore directly.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HistoryRepository(application.applicationContext)

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<HistoryMessage?>(null)
    val message: StateFlow<HistoryMessage?> = _message.asStateFlow()

    private val _sort = MutableStateFlow(HistorySort.NEWEST_FIRST)
    val sort: StateFlow<HistorySort> = _sort.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Show only videos from the last 30 days. */
    private val _recentOnly = MutableStateFlow(false)
    val recentOnly: StateFlow<Boolean> = _recentOnly.asStateFlow()

    private val _deletingIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingIds: StateFlow<Set<String>> = _deletingIds.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val thumbs: StateFlow<Map<String, Bitmap?>> = _thumbs.asStateFlow()
    private val thumbCache = LruCache<String, Bitmap>(24)
    private val thumbJobs = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            combine(repo.observeAll(), _sort, _query, _recentOnly) { rows, s, q, recent ->
                var list = rows
                if (recent) list = HistoryListLogic.recent(list, 30, System.currentTimeMillis())
                list = HistoryListLogic.filtered(list, q)
                HistoryListLogic.sorted(list, s)
            }.catch { e ->
                _uiState.value = HistoryUiState.Error(
                    e.message ?: "Could not load your videos.",
                )
            }.collect { list ->
                _uiState.value =
                    if (list.isEmpty()) HistoryUiState.Empty
                    else HistoryUiState.Loaded(list)
            }
        }
        refresh()
    }

    /**
     * Reconciles the database with the actual files: drops records whose
     * video vanished outside the app and sweeps orphan thumbnails.
     * Runs once per screen entry; never crashes the UI.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.refreshAvailability()
            } catch (_: Exception) {
            }
            try {
                val allIds = repo.getAll().map { it.id }.toSet()
                ThumbnailStore.sweepOrphans(getApplication(), allIds)
            } catch (_: Exception) {
            }
        }
    }

    fun setSort(sort: HistorySort) {
        _sort.value = sort
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setRecentOnly(recent: Boolean) {
        _recentOnly.value = recent
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            when (val r = repo.rename(id, newName)) {
                is RenameValidator.Result.Ok ->
                    _message.value = HistoryMessage.Info("Video renamed.")
                is RenameValidator.Result.Invalid ->
                    _message.value = HistoryMessage.Error(r.reason)
                is RenameValidator.Result.Duplicate ->
                    _message.value = HistoryMessage.Error(r.reason)
            }
        }
    }

    /**
     * Deletes a history item: the actual video file is removed first via the
     * repository; Room metadata and thumbnails are removed only after the
     * file deletion succeeds (or the file is confirmed missing). On failure
     * the record is kept and an error is surfaced.
     *
     * @param onResult invoked on the main thread with true when the video
     * was actually deleted, false when the file deletion failed.
     */
    fun delete(id: String, onResult: ((Boolean) -> Unit)? = null) {
        if (id in _deletingIds.value) return
        _deletingIds.value = _deletingIds.value + id
        viewModelScope.launch {
            val deleted = when (val r = repo.delete(id)) {
                is HistoryRepository.DeleteResult.Deleted -> {
                    thumbCache.remove(id)
                    _thumbs.value = _thumbs.value - id
                    _message.value = HistoryMessage.Info("Video deleted.")
                    true
                }
                is HistoryRepository.DeleteResult.FileDeleteFailed -> {
                    _message.value = HistoryMessage.Error(r.message)
                    false
                }
            }
            _deletingIds.value = _deletingIds.value - id
            onResult?.invoke(deleted)
        }
    }

    /**
     * Loads (or reuses) the cached thumbnail for one card. Called from the
     * list item's LaunchedEffect so only visible items decode bitmaps.
     */
    fun thumbnailFor(item: VideoHistoryItem) {
        val id = item.id
        if (_thumbs.value.containsKey(id)) return
        thumbCache.get(id)?.let {
            _thumbs.value = _thumbs.value + (id to it)
            return
        }
        if (thumbJobs.containsKey(id)) return
        thumbJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = ThumbnailStore.load(item.thumbnailPath)
                if (bmp != null) thumbCache.put(id, bmp)
                _thumbs.value = _thumbs.value + (id to bmp)
            } catch (_: Exception) {
                _thumbs.value = _thumbs.value + (id to null)
            } finally {
                thumbJobs.remove(id)
            }
        }
    }

    /** Detail screen accessor — loads one record by stable id. */
    fun detailFlow(id: String) = repo.observeById(id)
}
