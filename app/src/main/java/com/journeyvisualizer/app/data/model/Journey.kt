package com.journeyvisualizer.app.data.model

import kotlin.math.*

/** A single timestamped coordinate from the Timeline export. */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
)

data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLng: Double get() = (minLng + maxLng) / 2.0
}

/** The full parsed journey: one chronologically ordered point list. */
data class Journey(
    val title: String,
    val sourceName: String,
    val points: List<TrackPoint>,
) {
    val startMs: Long get() = points.firstOrNull()?.timeMs ?: 0L
    val endMs: Long get() = points.lastOrNull()?.timeMs ?: 0L

    val totalDistanceM: Double by lazy {
        var d = 0.0
        for (i in 1 until points.size) {
            d += haversineM(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        }
        d
    }

    val bounds: Bounds? by lazy {
        if (points.isEmpty()) return@lazy null
        var minLat = 90.0; var maxLat = -90.0
        var minLng = 180.0; var maxLng = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng)
        }
        // Guard against a zero-area box (single point journey).
        if (maxLat - minLat < 1e-6) { maxLat += 0.005; minLat -= 0.005 }
        if (maxLng - minLng < 1e-6) { maxLng += 0.005; minLng -= 0.005 }
        Bounds(minLat, maxLat, minLng, maxLng)
    }
}

fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
