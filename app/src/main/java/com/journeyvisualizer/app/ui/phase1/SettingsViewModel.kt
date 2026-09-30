package com.journeyvisualizer.app.ui.phase1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.journeyvisualizer.app.data.SettingsRepository
import com.journeyvisualizer.app.export.BitratePolicy
import com.journeyvisualizer.app.export.EncoderProbe
import com.journeyvisualizer.app.history.HistoryRepository
import com.journeyvisualizer.app.settings.AppSettings
import com.journeyvisualizer.app.settings.StorageOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 9: backing ViewModel for the Settings screen.
 *
 * - Exposes the persisted [AppSettings] as a StateFlow (survives rotation
 *   and process recreation via DataStore, not in-memory state).
 * - Computes storage statistics asynchronously off the main thread.
 * - "Clear cache" only removes directories listed in
 *   [StorageOwnership] — never videos, never the Room database, never the
 *   user's Timeline.json.
 * - "Reset settings" only resets preferences (see
 *   [SettingsRepository.resetUserPreferences]); history and videos are
 *   untouched.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app.applicationContext)

    val settings: StateFlow<AppSettings> = repo.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT)

    // -- storage ------------------------------------------------------------

    sealed interface StorageState {
        data object Loading : StorageState
        data class Ready(val info: StorageInfo) : StorageState
        data class Error(val message: String) : StorageState
    }

    data class StorageInfo(
        val videoCount: Int,
        val videosBytes: Long,
        val cacheBytes: Long,
        val cacheBreakdown: List<Pair<String, Long>>,
    )

    private val _storage = MutableStateFlow<StorageState>(StorageState.Loading)
    val storage: StateFlow<StorageState> = _storage

    private val _cacheClearedBytes = MutableStateFlow<Long?>(null)
    val cacheClearedBytes: StateFlow<Long?> = _cacheClearedBytes

    private val _resetDone = MutableStateFlow(false)
    val resetDone: StateFlow<Boolean> = _resetDone

    /**
     * Whether this device's encoder supports 4K (3840×2160 @ 30fps).
     * Null while probing. The resolution picker disables 4K with an
     * explanation when this is false — the setting never offers what the
     * Phase 7 renderer cannot encode.
     */
    private val _supports4k = MutableStateFlow<Boolean?>(null)
    val supports4k: StateFlow<Boolean?> = _supports4k

    init {
        refreshStorage()
        viewModelScope.launch(Dispatchers.Default) {
            _supports4k.value = runCatching {
                EncoderProbe.check(
                    width = 3840,
                    height = 2160,
                    fps = 30,
                    requestedBitrate = BitratePolicy.bitrateFor(3840, 2160, 30),
                ).ok
            }.getOrDefault(false)
        }
    }

    /** Recomputes storage stats off the main thread. */
    fun refreshStorage() {
        _storage.value = StorageState.Loading
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { computeStorageInfo() }
                _storage.value = StorageState.Ready(info)
            } catch (e: Exception) {
                _storage.value = StorageState.Error(
                    e.message?.take(160) ?: "Could not read storage information.",
                )
            }
        }
    }

    private suspend fun computeStorageInfo(): StorageInfo {
        val context = getApplication<Application>()
        var videoCount = 0
        var videosBytes = 0L
        runCatching {
            val repo = HistoryRepository(context)
            val items = repo.getAll()
            videoCount = items.size
            videosBytes = items.sumOf { it.sizeBytes.coerceAtLeast(0) }
        }
        val breakdown = mutableListOf<Pair<String, Long>>()
        var cacheBytes = 0L
        val cacheRoot = context.cacheDir ?: return StorageInfo(videoCount, videosBytes, 0L, emptyList())
        cacheRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && StorageOwnership.isClearableCachePath(dir.name)) {
                val size = dirSize(dir)
                breakdown += dir.name to size
                cacheBytes += size
            }
        }
        return StorageInfo(videoCount, videosBytes, cacheBytes, breakdown.sortedBy { it.first })
    }

    private fun dirSize(root: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 50_000) {
            guard++
            val f = stack.removeLast()
            if (f.isDirectory) {
                f.listFiles()?.let { stack.addAll(it) }
            } else {
                total += f.length()
            }
        }
        return total
    }

    /**
     * Clears only app-owned regenerable cache (thumbnails, tile caches).
     * Reports how many bytes were freed, then refreshes the stats.
     */
    fun clearCache() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                var total = 0L
                val cacheRoot = getApplication<Application>().cacheDir ?: return@withContext 0L
                cacheRoot.listFiles()?.forEach { dir ->
                    if (dir.isDirectory && StorageOwnership.isClearableCachePath(dir.name)) {
                        total += dirSize(dir)
                        dir.deleteRecursively()
                    }
                }
                total
            }
            _cacheClearedBytes.value = freed
            refreshStorage()
        }
    }

    fun consumeCacheCleared() {
        _cacheClearedBytes.value = null
    }

    /** Resets preferences only; videos/history are never touched. */
    fun resetSettings() {
        viewModelScope.launch {
            runCatching { repo.resetUserPreferences() }
            _resetDone.value = true
        }
    }

    fun consumeResetDone() {
        _resetDone.value = false
    }

    // -- settings writers ---------------------------------------------------

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            runCatching { repo.block() }
        }
    }
}
