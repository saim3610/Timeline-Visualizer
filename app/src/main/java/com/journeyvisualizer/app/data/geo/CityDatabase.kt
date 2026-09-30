package com.journeyvisualizer.app.data.geo

import android.content.Context
import com.journeyvisualizer.app.data.model.haversineM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class City(
    val name: String,
    val country: String,
    val lat: Double,
    val lng: Double,
    val population: Long,
)

data class CityStop(
    val city: City,
    /** When the journey first reached this city (epoch ms). */
    val arrivedAtMs: Long,
)

/**
 * Offline city lookup backed by the GeoNames cities15000 data bundled in
 * `assets/geonames/cities.tsv`. A 1° × 1° grid index keeps nearest-city
 * queries fast with no network at all.
 */
class CityDatabase(private val context: Context) {

    private var cities: List<City> = emptyList()
    private var grid: Map<Long, IntArray> = emptyMap()
    @Volatile private var loaded = false
    private val lock = Any()

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (loaded) return@synchronized
                load()
                loaded = true
            }
        }
    }

    private fun load() {
        val list = ArrayList<City>(35000)
        context.assets.open("geonames/cities.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEach
                val lat = p[1].toDoubleOrNull() ?: return@forEach
                val lng = p[2].toDoubleOrNull() ?: return@forEach
                val pop = p[4].toLongOrNull() ?: 0L
                list.add(City(p[0], p[3], lat, lng, pop))
            }
        }
        val tmp = HashMap<Long, MutableList<Int>>()
        list.forEachIndexed { i, c ->
            tmp.getOrPut(cellKey(c.lat, c.lng)) { ArrayList() }.add(i)
        }
        grid = tmp.mapValues { it.value.toIntArray() }
        cities = list
    }

    private fun cellKey(lat: Double, lng: Double): Long {
        val la = (lat + 90.0).toInt().coerceIn(0, 179)
        val ln = (lng + 180.0).toInt().coerceIn(0, 359)
        return la * 360L + ln
    }

    /**
     * Best city within [maxKm] of the point. When several match, the largest
     * population wins, so "Lahore" beats a nearby suburb.
     */
    fun nearestCity(lat: Double, lng: Double, maxKm: Double = 60.0): City? {
        if (!loaded || cities.isEmpty()) return null
        // 1° of latitude ≈ 111 km; scan enough cells to cover the radius.
        val cells = (maxKm / 111.0).toInt().coerceAtLeast(1) + 1
        val la0 = (lat + 90.0).toInt()
        val ln0 = (lng + 180.0).toInt()
        var best: City? = null
        var bestPop = -1L
        for (dLa in -cells..cells) {
            val la = (la0 + dLa).coerceIn(0, 179)
            for (dLn in -cells..cells) {
                val ln = ((ln0 + dLn) % 360 + 360) % 360
                val idx = grid[la * 360L + ln] ?: continue
                for (i in idx) {
                    val c = cities[i]
                    if (c.population <= bestPop) continue
                    if (haversineM(lat, lng, c.lat, c.lng) / 1000.0 <= maxKm) {
                        best = c
                        bestPop = c.population
                    }
                }
            }
        }
        return best
    }
}
