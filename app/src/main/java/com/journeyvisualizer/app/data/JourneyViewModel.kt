package com.journeyvisualizer.app.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.data.geo.CityDatabase
import com.journeyvisualizer.app.data.geo.CityStop
import com.journeyvisualizer.app.data.geo.VisitedCities
import com.journeyvisualizer.app.map.JourneyEngine
import com.journeyvisualizer.app.map.TileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Draft video configuration edited on the Create screen. */
data class VideoDraft(
    var startMs: Long? = null,
    var endMs: Long? = null,
    var durationSec: Int = 60,
    var formatKey: String = "SQUARE_1080",
    var cameraKey: String = "STEADY",
    var timeWarpKey: String = "LINEAR",
    var title: String = "My Journey",
)

class JourneyViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface ImportState {
        data object Idle : ImportState
        data object Loading : ImportState
        data class Loaded(val journey: Journey, val fileName: String, val warnings: List<String>) : ImportState
        data class Error(val message: String) : ImportState
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    val draft = VideoDraft()
    val settings = SettingsRepository(application)
    private val journal = JournalRepository(application)
    val journalEntries: StateFlow<List<JournalEntry>> = journal.entries
    private val cityDb = CityDatabase(application)
    private val _visitedCities = MutableStateFlow<List<CityStop>>(emptyList())
    val visitedCities: StateFlow<List<CityStop>> = _visitedCities

    /** Bumped whenever a new file is imported so screens reset their editors. */
    var draftRevision by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            draft.durationSec = settings.defaultDurationSec.first()
            draft.formatKey = settings.videoFormat.first()
            draft.cameraKey = settings.cameraMode.first()
            draft.timeWarpKey = settings.defaultTimeWarp.first()
            journal.refresh()
        }
    }

    fun importTimeline(uri: Uri) {
        _importState.value = ImportState.Loading
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                cr.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val fileName = queryDisplayName(uri) ?: "Timeline.json"
                val result = withContext(Dispatchers.IO) {
                    cr.openInputStream(uri)?.use { TimelineParser.parse(it, fileName) }
                } ?: throw IllegalStateException("Could not open file")
                val journey = result.journey
                if (journey == null || journey.points.size < 2) {
                    _importState.value = ImportState.Error(
                        (result.warnings + "Need at least 2 location points.")
                            .joinToString("\n")
                    )
                    return@launch
                }
                draft.startMs = journey.startMs
                draft.endMs = journey.endMs
                draft.title = applyTemplate(settings.titleTemplate.first(), journey)
                draftRevision++
                settings.setLastImport(uri.toString(), fileName)
                _importState.value = ImportState.Loaded(journey, fileName, result.warnings)
                // Keep the travel journal up to date automatically.
                journal.saveImport(fileName, journey)
                refreshVisitedCities(journey)
            } catch (e: SecurityException) {
                _importState.value = ImportState.Error("Permission to read that file was denied.")
            } catch (e: Exception) {
                _importState.value = ImportState.Error("Import failed: ${e.message}")
            }
        }
    }

    /** Loads journal entries (merged + deduped) as the current video draft. */
    fun loadJournalAsDraft(ids: List<String>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) { journal.loadMerged(ids) }
            if (merged == null || merged.points.size < 2) {
                onDone(false)
                return@launch
            }
            draft.startMs = merged.startMs
            draft.endMs = merged.endMs
            draft.title = merged.title
            draftRevision++
            _importState.value = ImportState.Loaded(merged, "Travel journal", emptyList())
            refreshVisitedCities(merged)
            onDone(true)
        }
    }

    fun deleteJournalEntry(id: String) {
        viewModelScope.launch { journal.delete(id) }
    }

    /**
     * Downloads the map tiles for the current draft range into the on-device
     * cache, so export can run offline. Progress is 0..1.
     */
    fun prefetchDraftTiles(onProgress: (Float) -> Unit, onDone: (Boolean) -> Unit) {
        val loaded = _importState.value as? ImportState.Loaded ?: run {
            onDone(false)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ranged = JourneyEngine(loaded.journey).filterRange(
                        draft.startMs ?: loaded.journey.startMs,
                        draft.endMs ?: loaded.journey.endMs,
                    )
                    val (w, h) = VIDEO_FORMATS[draft.formatKey] ?: (1080 to 1080)
                    val engine = JourneyEngine(ranged)
                    val cache = TileCache(getApplication<Application>().applicationContext)
                    val keys = engine.warmTileKeys(w, h)
                    keys.forEachIndexed { i, k ->
                        cache.getTile(k.z, k.x, k.y)
                        if (i % 4 == 0) onProgress(i.toFloat() / keys.size.coerceAtLeast(1))
                    }
                }
                onProgress(1f)
                onDone(true)
            } catch (_: Exception) {
                onDone(false)
            }
        }
    }

    fun clearImport() {
        _importState.value = ImportState.Idle
        _visitedCities.value = emptyList()
    }

    /** Reverse-geocodes the journey's stops against the offline city database. */
    private fun refreshVisitedCities(journey: Journey) {
        viewModelScope.launch {
            _visitedCities.value = try {
                VisitedCities.detect(journey, cityDb)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    return c.getString(0)
                }
            }
        return null
    }

    private fun applyTemplate(template: String, journey: Journey): String {
        val year = java.time.Instant.ofEpochMilli(journey.startMs)
            .atZone(java.time.ZoneId.systemDefault()).year.toString()
        return template
            .replace("{year}", year)
            .replace("{name}", android.os.Build.MODEL)
            .take(80)
    }
}
