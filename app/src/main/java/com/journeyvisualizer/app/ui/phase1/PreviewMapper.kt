package com.journeyvisualizer.app.ui.phase1

import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.geo.unresolvedLocation
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.ui.phase1.components.MapPin
import com.journeyvisualizer.app.ui.util.formatDateTime

/**
 * Derives honest preview pins from resolved Timeline points.
 *
 * Sampled points (first, last, evenly spaced) are projected from their real
 * latitude/longitude into 0..1 map-preview fractions. Each pin carries its
 * [ResolvedLocation]: when a place was found the pin shows the real city and
 * country, otherwise it falls back to the honest "Location N" label — city
 * names are never invented.
 */
fun List<ResolvedPoint>.toPreviewPins(max: Int = 6): List<MapPin> {
    if (isEmpty()) return emptyList()
    var minLat = 90.0; var maxLat = -90.0
    var minLng = 180.0; var maxLng = -180.0
    for (rp in this) {
        val p = rp.point
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lng < minLng) minLng = p.lng
        if (p.lng > maxLng) maxLng = p.lng
    }
    if (maxLat - minLat < 1e-6) { maxLat += 0.005; minLat -= 0.005 }
    if (maxLng - minLng < 1e-6) { maxLng += 0.005; minLng -= 0.005 }
    val spanLat = maxLat - minLat
    val spanLng = maxLng - minLng
    // 12% inset so pins never sit exactly on the preview edge.
    fun x(lng: Double): Float =
        (0.12 + 0.76 * (lng - minLng) / spanLng).toFloat().coerceIn(0.05f, 0.95f)
    // North at the top.
    fun y(lat: Double): Float =
        (0.12 + 0.76 * (maxLat - lat) / spanLat).toFloat().coerceIn(0.05f, 0.95f)

    val n = size
    val count = minOf(max, n).coerceAtLeast(1)
    val indices = if (count == 1) {
        listOf(0)
    } else {
        List(count) { k -> ((k.toLong() * (n - 1)) / (count - 1)).toInt() }.distinct()
    }
    return indices.mapIndexed { k, pi ->
        val rp = this[pi]
        val loc = rp.location
        MapPin(
            name = loc.name ?: "Location ${k + 1}",
            dateTime = formatDateTime(rp.point.timeMs),
            x = x(rp.point.lng),
            y = y(rp.point.lat),
            location = loc,
            // Stable timeline index: lets the event list and the real map
            // (Phase 4) select the same point without name matching.
            pointIndex = pi,
        )
    }
}

/**
 * Fallback pins without location resolution (used when the GeoNames data
 * could not be loaded). Labels stay honest: "Location 1..N", never invented.
 */
fun Journey.toPreviewPins(max: Int = 6): List<MapPin> {
    if (points.isEmpty()) return emptyList()
    val unresolved = points.map { p -> ResolvedPoint(p, unresolvedLocation(p.lat, p.lng)) }
    return unresolved.toPreviewPins(max)
}
