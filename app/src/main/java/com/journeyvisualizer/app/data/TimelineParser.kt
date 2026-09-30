package com.journeyvisualizer.app.data

import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.data.model.TrackPoint
import com.journeyvisualizer.app.data.model.haversineM
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime

data class ParseResult(
    val journey: Journey?,
    val pointCount: Int,
    val outlierCount: Int,
    val warnings: List<String>,
    /** Top-level timeline entries the file contained (records or segments). */
    val recordCount: Int = 0,
    /** Points extracted before de-duplication / outlier filtering. */
    val rawPointCount: Int = 0,
    /** "direct" for array exports, "semantic" for { semanticSegments } exports. */
    val formatName: String = "",
    /** Machine-readable failure reason when [journey] is null. */
    val failureReason: FailureReason? = null,
)

/** Why parsing produced no journey; used by the import UI for error copy. */
enum class FailureReason {
    /** The file was empty. */
    EMPTY_FILE,
    /** The file could not be opened or read. */
    READ_ERROR,
    /** Not JSON at all, or JSON that could not be read. */
    NOT_JSON,
    /** Valid JSON but not a recognizable Timeline export structure. */
    NOT_TIMELINE,
    /** Recognized structure, but no usable geographic points. */
    NO_POINTS,
    /** The file is too large to process safely on this device. */
    TOO_LARGE,
}

/**
 * Parses a Google Timeline export (`Timeline.json`) into a [Journey].
 *
 * Tolerates the current direct-array exports as well as the older
 * `{ "semanticSegments": [...] }` shape, visits, and several coordinate
 * spellings ("lat,lng" strings, E7 ints, `geo:` URIs, `latLng` objects).
 */
object TimelineParser {

    /** Faster than this over a short gap is treated as a GPS glitch. */
    private const val MAX_OUTLIER_SPEED_MPS = 250.0
    private const val MAX_OUTLIER_GAP_MS = 120_000L

    fun parse(input: InputStream, sourceName: String): ParseResult =
        parse(input, sourceName, {})

    /**
     * Same as [parse], but reports coarse 0..1 progress while iterating
     * segments so import UI can show meaningful (not fake) progress.
     */
    fun parse(input: InputStream, sourceName: String, onProgress: (Float) -> Unit): ParseResult {
        val text = input.bufferedReader().use { it.readText() }.trim().removePrefix("\uFEFF")
        return parseText(text, sourceName, onProgress)
    }

    /** Parses already-read JSON text; the [InputStream] overloads delegate here. */
    fun parseText(
        text: String,
        sourceName: String,
        onProgress: (Float) -> Unit = {},
    ): ParseResult {
        val warnings = mutableListOf<String>()

        var formatName = ""
        val segments: JSONArray = try {
            when {
                text.startsWith("[") -> {
                    formatName = "direct"
                    JSONArray(text)
                }
                text.startsWith("{") -> {
                    val root = JSONObject(text)
                    val semantic = root.optJSONArray("semanticSegments")
                    if (semantic != null) {
                        formatName = "semantic"
                        semantic
                    } else {
                        // Classic Google Takeout "Location History" shape:
                        // { "locations": [{ timestampMs, latitudeE7, longitudeE7, ... }] }.
                        // Entries parse through the same direct-point path as
                        // top-level arrays (timestampMs + E7 are already handled).
                        val locations = root.optJSONArray("locations")
                            ?: return ParseResult(
                                null, 0, 0,
                                listOf("This JSON is not a Timeline export (no semanticSegments or locations)."),
                                failureReason = FailureReason.NOT_TIMELINE,
                            )
                        formatName = "locations"
                        locations
                    }
                }
                else -> return ParseResult(
                    null, 0, 0, listOf("This file is not a Timeline.json export."),
                    failureReason = FailureReason.NOT_JSON,
                )
            }
        } catch (e: Exception) {
            return ParseResult(
                null, 0, 0, listOf("Could not read JSON: ${e.message}"),
                failureReason = FailureReason.NOT_JSON,
            )
        }
        onProgress(0.1f)

        val raw = ArrayList<TrackPoint>(segments.length() * 4)
        val total = segments.length().coerceAtLeast(1)
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val segStart = parseTime(seg.optString("startTime", null))
            val segEnd = parseTime(seg.optString("endTime", null))

            // Direct-array exports: each top-level entry is itself a point
            // (latitudeE7/longitudeE7, latitude/longitude, or "point" strings).
            // Semantic segments never carry top-level coordinates, so this is
            // a no-op for them.
            parseCoordinate(seg)?.let { (lat, lng) ->
                timeOf(seg)?.let { t -> raw.add(TrackPoint(lat, lng, t)) }
            }

            // Main path, plus paths nested under "activity" in some exports.
            collectPath(seg.optJSONArray("timelinePath"), segStart, segEnd, raw)
            seg.optJSONObject("activity")?.let { act ->
                collectPath(act.optJSONArray("timelinePath"), segStart, segEnd, raw)
            }

            // Visits become dwell points so the marker pauses there.
            seg.optJSONObject("visit")?.let { collectVisit(it, raw) }
            seg.optJSONArray("visits")?.let { visits ->
                for (v in 0 until visits.length()) {
                    visits.optJSONObject(v)?.let { collectVisit(it, raw) }
                }
            }
            if (i % 32 == 0) onProgress(0.1f + 0.8f * (i + 1) / total)
        }
        onProgress(0.9f)

        if (raw.isEmpty()) {
            onProgress(1f)
            return ParseResult(
                null, 0, 0,
                listOf("No location points found in this export.") + warnings,
                recordCount = segments.length(),
                rawPointCount = 0,
                formatName = formatName,
                failureReason = FailureReason.NO_POINTS,
            )
        }

        raw.sortBy { it.timeMs }

        // Drop exact consecutive duplicates and implausible GPS jumps.
        val clean = ArrayList<TrackPoint>(raw.size)
        var outliers = 0
        var prev: TrackPoint? = null
        for (p in raw) {
            val q = prev
            if (q != null) {
                if (p.timeMs <= q.timeMs) continue
                val sameSpot = p.lat == q.lat && p.lng == q.lng
                val dt = p.timeMs - q.timeMs
                if (!sameSpot && dt < MAX_OUTLIER_GAP_MS) {
                    val speed = haversineM(q.lat, q.lng, p.lat, p.lng) / (dt / 1000.0)
                    if (speed > MAX_OUTLIER_SPEED_MPS) {
                        outliers++
                        continue
                    }
                }
                if (sameSpot && dt < 60_000L) continue
            }
            clean.add(p)
            prev = p
        }

        if (outliers > 0) {
            warnings.add("Ignored $outliers implausible GPS point${if (outliers == 1) "" else "s"}.")
        }
        if (clean.size < 2) {
            warnings.add("Only ${clean.size} usable point${if (clean.size == 1) "" else "s"} found.")
        }

        val journey = Journey(
            title = "My Journey",
            sourceName = sourceName,
            points = clean,
        )
        onProgress(1f)
        return ParseResult(
            journey, clean.size, outliers, warnings,
            recordCount = segments.length(),
            rawPointCount = raw.size,
            formatName = formatName,
        )
    }

    private fun collectPath(
        path: JSONArray?,
        segStart: Long?,
        segEnd: Long?,
        out: MutableList<TrackPoint>,
    ) {
        if (path == null) return
        val n = path.length()
        for (j in 0 until n) {
            val entry = path.opt(j) ?: continue
            val (lat, lng) = parseCoordinate(entry) ?: continue
            val t = timeOf(entry)
                ?: interpolateTime(segStart, segEnd, j, n)
                ?: continue
            out.add(TrackPoint(lat, lng, t))
        }
    }

    private fun collectVisit(visit: JSONObject, out: MutableList<TrackPoint>) {
        val start = parseTime(visit.optString("startTime", null)) ?: return
        val end = parseTime(visit.optString("endTime", null)) ?: start
        val coord = findVisitCoordinate(visit) ?: return
        out.add(TrackPoint(coord.first, coord.second, start))
        if (end > start) out.add(TrackPoint(coord.first, coord.second, end))
    }

    private fun findVisitCoordinate(visit: JSONObject): Pair<Double, Double>? {
        val candidates = listOf(
            visit.optJSONObject("topCandidate")?.optJSONObject("placeLocation"),
            visit.optJSONObject("placeLocation"),
            visit.optJSONObject("location"),
            visit,
        )
        for (c in candidates) {
            c ?: continue
            parseLatLngString(c.optString("latLng", null))?.let { return it }
            parseCoordinate(c)?.let { return it }
        }
        return null
    }

    private fun timeOf(entry: Any?): Long? {
        if (entry !is JSONObject) return null
        for (key in listOf("time", "startTime", "timestamp", "timestampMs")) {
            parseTime(entry.optString(key, null))?.let { return it }
        }
        return null
    }

    private fun interpolateTime(start: Long?, end: Long?, index: Int, count: Int): Long? {
        if (start == null || end == null || end <= start || count <= 1) return start ?: end
        val frac = index.toDouble() / (count - 1).toDouble()
        return (start + frac * (end - start)).toLong()
    }

    private fun parseCoordinate(entry: Any?): Pair<Double, Double>? {
        when (entry) {
            is String -> return parseLatLngString(entry)
            is JSONObject -> {
                // E7 integer pair, direct or nested under "point".
                for (obj in listOf(entry, entry.optJSONObject("point"))) {
                    obj ?: continue
                    if (obj.has("latitudeE7") && obj.has("longitudeE7")) {
                        val lat = obj.optDouble("latitudeE7", Double.NaN) / 1e7
                        val lng = obj.optDouble("longitudeE7", Double.NaN) / 1e7
                        if (valid(lat, lng)) return lat to lng
                    }
                    if (obj.has("latitude") && obj.has("longitude")) {
                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lng = obj.optDouble("longitude", Double.NaN)
                        if (valid(lat, lng)) return lat to lng
                    }
                    // Shorthand spellings used by some exports ("lat"/"lng", "lat"/"lon").
                    for ((latKey, lngKey) in listOf("lat" to "lng", "lat" to "lon")) {
                        if (obj.has(latKey) && obj.has(lngKey)) {
                            val lat = obj.optDouble(latKey, Double.NaN)
                            val lng = obj.optDouble(lngKey, Double.NaN)
                            if (valid(lat, lng)) return lat to lng
                        }
                    }
                    parseLatLngString(obj.optString("latLng", null))?.let { return it }
                }
                parseLatLngString(entry.optString("point", null))?.let { return it }
            }
        }
        return null
    }

    private fun parseLatLngString(s: String?): Pair<Double, Double>? {
        if (s.isNullOrBlank()) return null
        val cleaned = s.trim().removePrefix("geo:").substringBefore("?")
        val parts = cleaned.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        return if (valid(lat, lng)) lat to lng else null
    }

    private fun valid(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        // Epoch millis, just in case.
        t.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }
        return try {
            Instant.parse(t).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(t).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
