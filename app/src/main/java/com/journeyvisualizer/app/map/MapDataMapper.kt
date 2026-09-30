package com.journeyvisualizer.app.map

import com.journeyvisualizer.app.animation.AnimationPoint
import com.journeyvisualizer.app.data.geo.ResolvedPoint
import com.journeyvisualizer.app.data.model.Bounds

/**
 * Convert resolved points to map points. Every input point becomes one
 * [MapPoint] with its ORIGINAL list index, even unmappable ones, so the
 * index doubles as the stable timeline ↔ map ID. The renderer skips
 * points where [MapPoint.isMappable] is false.
 */
fun List<ResolvedPoint>.toMapPoints(): List<MapPoint> =
    mapIndexed { index, rp ->
        MapPoint(
            index = index,
            lat = rp.point.lat,
            lng = rp.point.lng,
            timeMs = rp.point.timeMs,
            location = rp.location,
        )
    }

/** Animation points → map points, keeping the stable source id as the index. */
fun List<AnimationPoint>.toMapPointsFromAnimation(): List<MapPoint> =
    map { ap ->
        MapPoint(
            index = ap.id,
            lat = ap.lat,
            lng = ap.lng,
            timeMs = ap.timestampMs,
            location = ap.source.location,
        )
    }

/** Single animation point → map point (Phase 6 preview map). */
fun AnimationPoint.toMapPoint(): MapPoint = listOf(this).toMapPointsFromAnimation().first()

/** Only points that can actually be drawn on a Web-Mercator map. */
fun List<MapPoint>.mappable(): List<MapPoint> = filter { it.isMappable }

/**
 * Pure timeline → map data mapping (Phase 4). No Android, no map library:
 * everything here is JVM-testable.
 *
 * The resolved-point list is already chronological (TimelineParser emits
 * points in timestamp order and Phase 3 preserves it), so the route simply
 * follows list order. Raw coordinates are carried through untouched —
 * GeoNames stays metadata.
 */
object MapDataMapper {

    /**
     * Geographic bounds of the mappable points, or null when there is
     * nothing to show. Naive min/max (same convention as Journey.bounds);
     * journeys crossing the antimeridian fit the long way around — noted as
     * a limitation rather than silently "fixed".
     */
    fun boundsOf(points: List<MapPoint>): Bounds? {
        val mappable = points.mappable()
        if (mappable.isEmpty()) return null
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0
        for (p in mappable) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        return Bounds(minLat, maxLat, minLng, maxLng)
    }

    /**
     * Display-only decimation of a chronological point list to at most
     * [maxPoints] points, keeping the first and last. The underlying
     * timeline records are never touched — this only thins what is drawn.
     */
    fun decimateChronological(points: List<MapPoint>, maxPoints: Int): List<MapPoint> {
        require(maxPoints >= 2)
        if (points.size <= maxPoints) return points
        val stride = points.size.toDouble() / (maxPoints - 1)
        val out = ArrayList<MapPoint>(maxPoints)
        for (i in 0 until maxPoints - 1) out.add(points[(i * stride).toInt()])
        out.add(points.last())
        return out
    }
}
