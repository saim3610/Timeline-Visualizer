package com.journeyvisualizer.app.data.geo

import kotlin.math.cos
import kotlin.math.max

/**
 * Simple grid-based spatial index over the bundled GeoNames cities.
 *
 * The dataset is ~34k points, so a full scan per lookup would be wasteful
 * across thousands of Timeline points. A 1° × 1° grid lets a lookup examine
 * only the cells overlapped by its search radius — typically a handful of
 * cells holding tens of candidates — instead of all 34k records.
 *
 * No spatial database is introduced; the index is built once per session
 * and held in memory next to the city list.
 */
class GeoGridIndex(
    private val cities: List<GeoCity>,
    private val cellDegrees: Double = 1.0,
) {
    private val rows = (180.0 / cellDegrees).toInt().coerceAtLeast(1)
    private val cols = (360.0 / cellDegrees).toInt().coerceAtLeast(1)

    /** Cell -> indices into [cities]. Built once at construction. */
    private val cells: Array<IntArray>

    init {
        val tmp = HashMap<Long, MutableList<Int>>(cities.size / 4)
        cities.forEachIndexed { i, c ->
            tmp.getOrPut(cellKey(c.lat, c.lng)) { ArrayList(4) }.add(i)
        }
        cells = Array(rows * cols) { idx ->
            tmp[idx.toLong()]?.toIntArray() ?: IntArray(0)
        }
    }

    private fun cellKey(lat: Double, lng: Double): Long {
        val r = ((lat + 90.0) / cellDegrees).toInt().coerceIn(0, rows - 1)
        val c = ((lng + 180.0) / cellDegrees).toInt().coerceIn(0, cols - 1)
        return r * cols.toLong() + c
    }

    /**
     * Invokes [action] with the index of every city inside the rectangle
     * covering [radiusKm] around ([lat], [lng]).
     *
     * The longitude span is widened by 1/cos(latitude) because degrees of
     * longitude shrink toward the poles; the antimeridian is handled by
     * splitting the longitude range when it crosses ±180°.
     */
    fun forEachInRadius(
        lat: Double,
        lng: Double,
        radiusKm: Double,
        action: (cityIndex: Int) -> Unit,
    ) {
        val dLat = radiusKm / 111.0
        // Guard cos() near the poles so the span stays finite.
        val kmPerLngDeg = 111.0 * max(cos(Math.toRadians(lat)), 0.15)
        val dLng = radiusKm / kmPerLngDeg

        val minLat = (lat - dLat).coerceIn(-90.0, 90.0)
        val maxLat = (lat + dLat).coerceIn(-90.0, 90.0)
        val r0 = ((minLat + 90.0) / cellDegrees).toInt().coerceIn(0, rows - 1)
        val r1 = ((maxLat + 90.0) / cellDegrees).toInt().coerceIn(0, rows - 1)

        // One or two longitude ranges (second when crossing the antimeridian).
        val ranges = ArrayList<Pair<Int, Int>>(2)
        val rawMin = lng - dLng
        val rawMax = lng + dLng
        if (rawMin < -180.0 || rawMax > 180.0) {
            val nMin = ((rawMin + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            val nMax = ((rawMax + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            // Wrapped: [nMin, 180] and [-180, nMax].
            ranges.add(
                ((nMin + 180.0) / cellDegrees).toInt().coerceIn(0, cols - 1) to (cols - 1)
            )
            ranges.add(
                0 to ((nMax + 180.0) / cellDegrees).toInt().coerceIn(0, cols - 1)
            )
        } else {
            ranges.add(
                ((rawMin + 180.0) / cellDegrees).toInt().coerceIn(0, cols - 1) to
                    ((rawMax + 180.0) / cellDegrees).toInt().coerceIn(0, cols - 1)
            )
        }

        for (r in r0..r1) {
            for ((c0, c1) in ranges) {
                for (c in c0..c1) {
                    val bucket = cells[r * cols + c]
                    for (i in bucket) action(i)
                }
            }
        }
    }

    fun city(index: Int): GeoCity = cities[index]
    val size: Int get() = cities.size
}
