package com.journeyvisualizer.app.data.geo

import com.journeyvisualizer.app.data.model.Journey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Figures out which cities a journey visited: samples the route over time,
 * reverse-geocodes each sample against the offline city database, and
 * collapses consecutive duplicates.
 */
object VisitedCities {

    /** Max cities reported for one journey. */
    private const val MAX_STOPS = 6

    suspend fun detect(journey: Journey, db: CityDatabase): List<CityStop> =
        withContext(Dispatchers.IO) {
            db.ensureLoaded()
            val pts = journey.points
            if (pts.size < 2) return@withContext emptyList()
            val span = (journey.endMs - journey.startMs).coerceAtLeast(1L)
            // ~1 sample per 3 hours of travel, between 2 and 12 samples.
            val samples = ((span / 10_800_000L).toInt() + 2).coerceIn(2, 12)
            val stops = ArrayList<CityStop>()
            for (s in 0 until samples) {
                val t = journey.startMs + span * s / (samples - 1)
                // Points are chronological; nearest-in-time sample is close enough.
                val p = pts.minByOrNull { abs(it.timeMs - t) } ?: continue
                val city = db.nearestCity(p.lat, p.lng) ?: continue
                val last = stops.lastOrNull()
                if (last != null && last.city.name == city.name &&
                    last.city.country == city.country
                ) {
                    continue
                }
                stops.add(CityStop(city, p.timeMs))
            }
            if (stops.size <= MAX_STOPS) {
                stops
            } else {
                // Keep first and last, thin out the middle.
                listOf(stops.first()) +
                    stops.drop(1).dropLast(1).take(MAX_STOPS - 2) +
                    listOf(stops.last())
            }
        }

    /** "Lahore → Islamabad" style title suggestion. */
    fun titleFor(stops: List<CityStop>): String =
        stops.map { it.city.name }.distinct().take(3).joinToString(" → ")
}
