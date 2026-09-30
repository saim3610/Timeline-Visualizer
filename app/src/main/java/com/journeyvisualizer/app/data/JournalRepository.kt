package com.journeyvisualizer.app.data

import android.content.Context
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.data.model.TrackPoint
import com.journeyvisualizer.app.data.model.haversineM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class JournalEntry(
    val id: String,
    val label: String,
    val importedAtMs: Long,
    val startMs: Long,
    val endMs: Long,
    val pointCount: Int,
    val distanceM: Double,
)

/**
 * The Travel Journal: every imported Timeline.json is kept in the app's
 * private storage (never uploaded). Entries can later be combined into a
 * single video — overlapping exports are merged and near-duplicate points
 * are dropped, so importing a newer export of the same trip just works.
 */
class JournalRepository(private val context: Context) {

    private val dir: File by lazy { File(context.filesDir, "journal").apply { mkdirs() } }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    val entries: StateFlow<List<JournalEntry>> = _entries

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { _entries.value = readIndex().sortedByDescending { it.importedAtMs } }
    }

    /**
     * Saves an import to the journal. Returns null when this exact import
     * (same time range and point count) is already journaled.
     */
    suspend fun saveImport(label: String, journey: Journey): JournalEntry? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = readIndex().toMutableList()
                val duplicate = list.any {
                    it.startMs == journey.startMs && it.endMs == journey.endMs &&
                        it.pointCount == journey.points.size
                }
                if (duplicate) {
                    _entries.value = list.sortedByDescending { it.importedAtMs }
                    return@withContext null
                }
                val entry = JournalEntry(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    importedAtMs = System.currentTimeMillis(),
                    startMs = journey.startMs,
                    endMs = journey.endMs,
                    pointCount = journey.points.size,
                    distanceM = journey.totalDistanceM,
                )
                writePoints(entry.id, journey.points)
                list.add(entry)
                writeIndex(list)
                _entries.value = list.sortedByDescending { it.importedAtMs }
                entry
            }
        }

    /** Merges the given entries into one journey, or null when too few points. */
    suspend fun loadMerged(ids: List<String>): Journey? = withContext(Dispatchers.IO) {
        val all = ids.flatMap { readPoints(it) }
        if (all.size < 2) return@withContext null
        Journey(
            title = "Combined journey",
            sourceName = "journal",
            points = mergeDedupe(all),
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex().filterNot { it.id == id }
            writeIndex(list)
            File(dir, "$id.json").delete()
            _entries.value = list.sortedByDescending { it.importedAtMs }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            _entries.value = emptyList()
        }
    }

    // ---- private storage ----

    private fun indexFile() = File(dir, "index.json")

    private fun readIndex(): List<JournalEntry> {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                JournalEntry(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    importedAtMs = o.getLong("importedAtMs"),
                    startMs = o.getLong("startMs"),
                    endMs = o.getLong("endMs"),
                    pointCount = o.getInt("pointCount"),
                    distanceM = o.getDouble("distanceM"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(list: List<JournalEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("importedAtMs", e.importedAtMs)
                    .put("startMs", e.startMs)
                    .put("endMs", e.endMs)
                    .put("pointCount", e.pointCount)
                    .put("distanceM", e.distanceM)
            )
        }
        indexFile().writeText(arr.toString())
    }

    private fun writePoints(id: String, points: List<TrackPoint>) {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONArray().put(p.lat).put(p.lng).put(p.timeMs))
        }
        File(dir, "$id.json").writeText(JSONObject().put("points", arr).toString())
    }

    private fun readPoints(id: String): List<TrackPoint> {
        val f = File(dir, "$id.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).getJSONArray("points")
            List(arr.length()) { i ->
                val a = arr.getJSONArray(i)
                TrackPoint(a.getDouble(0), a.getDouble(1), a.getLong(2))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * Merges overlapping exports: chronological order, dropping points
         * within a minute and 100 m of the previous kept point (typical of
         * re-exported duplicates).
         */
        fun mergeDedupe(points: List<TrackPoint>): List<TrackPoint> {
            if (points.isEmpty()) return points
            val sorted = points.sortedBy { it.timeMs }
            val out = ArrayList<TrackPoint>(sorted.size)
            for (p in sorted) {
                val last = out.lastOrNull()
                if (last != null && p.timeMs - last.timeMs < 60_000 &&
                    haversineM(last.lat, last.lng, p.lat, p.lng) < 100.0
                ) {
                    continue
                }
                out.add(p)
            }
            return out
        }
    }
}
