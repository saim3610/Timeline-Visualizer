package com.journeyvisualizer.app.data.geo

import com.journeyvisualizer.app.data.model.TrackPoint
import com.journeyvisualizer.app.data.model.haversineM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Offline reverse-geocoder over the bundled GeoNames dataset.
 *
 * Pipeline per coordinate:
 *
 * ```
 * coordinate -> normalize/validate -> cache -> grid index -> haversine
 *           -> nearest (population tie-break) -> quality flag -> result
 * ```
 *
 * Design notes:
 * - **Distance is primary.** The nearest place wins; population only breaks
 *   near-ties (see [LocationResolutionConfig.tieBreakerEpsilonKm]), so a
 *   town 2 km away is never beaten by a city 25 km away.
 * - **Haversine** (via [haversineM]) for all geographic distances — never
 *   Euclidean degrees.
 * - **Cache:** an LRU map of rounded-coordinate -> matched city index.
 *   Repeated Timeline coordinates (very common) never re-scan the index.
 *   The cached value is the *match*, not the result: distance and quality
 *   are recomputed against the exact query coordinate, so cache rounding
 *   never degrades accuracy.
 * - **Batch:** [resolveAll] dedupes points by cache key, resolves each
 *   unique coordinate once, and maps results back — thousands of points,
 *   one lookup per unique location.
 *
 * Thread-safe: [resolve] may be called from any thread.
 */
class LocationResolver(
    private val repository: GeoNamesRepository,
    val config: LocationResolutionConfig = repository.config,
) {
    private val index: GeoGridIndex = repository.index

    /** Rounded coordinate key -> city index, or -1 for "no match". */
    private val cache = object : LinkedHashMap<Long, Int>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>): Boolean =
            size > config.cacheMaxEntries
    }

    /** Diagnostics (also handy for tests): how the cache is behaving. */
    @Volatile var cacheHits: Long = 0L
        private set
    @Volatile var cacheMisses: Long = 0L
        private set
    @Volatile var lookups: Long = 0L
        private set

    // -- Single resolution ---------------------------------------------------

    /**
     * Resolves one coordinate. Never throws for bad input: out-of-range or
     * NaN coordinates yield an UNRESOLVED result.
     */
    fun resolve(lat: Double, lng: Double): ResolvedLocation {
        if (!isValidCoordinate(lat, lng)) {
            return ResolvedLocation(lat, lng, null, null, Double.NaN, MatchQuality.UNRESOLVED)
        }
        if (repository.isEmpty) {
            return ResolvedLocation(lat, lng, null, null, Double.NaN, MatchQuality.UNRESOLVED)
        }
        val cityIndex = matchIndex(lat, lng)
        return if (cityIndex < 0) {
            ResolvedLocation(lat, lng, null, null, Double.NaN, MatchQuality.UNRESOLVED)
        } else {
            toResult(lat, lng, index.city(cityIndex))
        }
    }

    /**
     * Matched city index for a coordinate (-1 = none). Backs both [resolve]
     * and [resolveAll]; results are always built from the *exact* query
     * coordinate, so cache rounding never degrades accuracy.
     */
    private fun matchIndex(lat: Double, lng: Double): Int {
        val key = cacheKey(lat, lng)
        val cached: Int? = synchronized(cache) { cache[key] }
        if (cached != null) {
            cacheHits++
            return cached
        }
        cacheMisses++
        val cityIndex = findNearestIndex(lat, lng)
        lookups++
        synchronized(cache) { cache[key] = cityIndex }
        return cityIndex
    }

    // -- Batch resolution ----------------------------------------------------

    /**
     * Resolves every point in [points], preserving order and returning one
     * [ResolvedPoint] per input point. Duplicate/nearby coordinates share a
     * single index lookup via the cache.
     *
     * Runs on [Dispatchers.Default]; [onProgress] receives (done, total)
     * over *unique* coordinates so the UI can show honest progress like
     * "1,250 / 3,842". Cooperative cancellation is checked between chunks.
     *
     * The input points are never modified — raw coordinates stay intact.
     */
    suspend fun resolveAll(
        points: List<TrackPoint>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResolvedPoint> = withContext(Dispatchers.Default) {
        if (points.isEmpty()) return@withContext emptyList()

        // Group input indices by cache key: one lookup per unique coordinate.
        val groups = LinkedHashMap<Long, MutableList<Int>>(points.size / 2 + 1)
        points.forEachIndexed { i, p ->
            val key = if (isValidCoordinate(p.lat, p.lng)) cacheKey(p.lat, p.lng) else Long.MIN_VALUE + i
            groups.getOrPut(key) { ArrayList() }.add(i)
        }

        val locations = arrayOfNulls<ResolvedLocation>(points.size)
        var done = 0
        val total = groups.size
        for ((_, idxs) in groups) {
            coroutineContext.ensureActive()
            val first = points[idxs.first()]
            // matchIndex() consults the cache, so already-known keys are free.
            val cityIndex = if (isValidCoordinate(first.lat, first.lng) && !repository.isEmpty) {
                matchIndex(first.lat, first.lng)
            } else {
                -1
            }
            val city = if (cityIndex >= 0) index.city(cityIndex) else null
            for (i in idxs) {
                val p = points[i]
                locations[i] = if (city == null || !isValidCoordinate(p.lat, p.lng)) {
                    ResolvedLocation(p.lat, p.lng, null, null, Double.NaN, MatchQuality.UNRESOLVED)
                } else {
                    // Built from the point's EXACT coordinates, not the
                    // rounded cache key, so near-duplicates keep full accuracy.
                    toResult(p.lat, p.lng, city)
                }
            }
            done++
            if (done % 64 == 0 || done == total) onProgress(done, total)
        }
        points.mapIndexed { i, p -> ResolvedPoint(p, locations[i]!!) }
    }

    // -- Core lookup ---------------------------------------------------------

    /**
     * Nearest city index within [LocationResolutionConfig.maxMatchKm],
     * or -1. Distance decides; population only breaks near-ties.
     */
    private fun findNearestIndex(lat: Double, lng: Double): Int {
        var bestIdx = -1
        var bestDistKm = Double.MAX_VALUE
        index.forEachInRadius(lat, lng, config.maxMatchKm) { i ->
            val c = index.city(i)
            val d = haversineM(lat, lng, c.lat, c.lng) / 1000.0
            if (d < bestDistKm) {
                bestDistKm = d
                bestIdx = i
            }
        }
        if (bestIdx < 0) return -1
        // Tie-break: within epsilon of the nearest, prefer the larger place
        // (handles duplicate metro entries without letting a far big city win).
        val eps = config.tieBreakerEpsilonKm
        if (eps > 0) {
            var tieIdx = bestIdx
            var tiePop = index.city(bestIdx).population
            index.forEachInRadius(lat, lng, config.maxMatchKm) { i ->
                val c = index.city(i)
                val d = haversineM(lat, lng, c.lat, c.lng) / 1000.0
                if (d <= bestDistKm + eps && c.population > tiePop) {
                    tiePop = c.population
                    tieIdx = i
                }
            }
            bestIdx = tieIdx
        }
        return bestIdx
    }

    private fun toResult(lat: Double, lng: Double, city: GeoCity): ResolvedLocation {
        val distKm = haversineM(lat, lng, city.lat, city.lng) / 1000.0
        return recomputeQuality(
            ResolvedLocation(lat, lng, city.name, city.country, distKm, MatchQuality.CLOSE)
        )
    }

    private fun recomputeQuality(loc: ResolvedLocation): ResolvedLocation {
        if (loc.name == null) return loc // Already unresolved.
        val q = when {
            loc.distanceKm <= config.closeMatchKm -> MatchQuality.CLOSE
            loc.distanceKm <= config.maxMatchKm -> MatchQuality.APPROXIMATE
            else -> MatchQuality.UNRESOLVED
        }
        return if (q == MatchQuality.UNRESOLVED) {
            loc.copy(name = null, country = null, distanceKm = Double.NaN, matchQuality = q)
        } else {
            loc.copy(matchQuality = q)
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private fun isValidCoordinate(lat: Double, lng: Double): Boolean =
        !lat.isNaN() && !lng.isNaN() &&
            lat in -90.0..90.0 && lng in -180.0..180.0

    private fun cacheKey(lat: Double, lng: Double): Long {
        val factor = 10.0.pow(config.cacheKeyDecimals)
        // Shift into non-negative ranges so the combined key is collision-free.
        val la = (lat * factor).roundToLong() + (90 * factor).roundToLong()
        val ln = (lng * factor).roundToLong() + (180 * factor).roundToLong()
        return la * (360 * factor).roundToLong() + ln
    }
}
