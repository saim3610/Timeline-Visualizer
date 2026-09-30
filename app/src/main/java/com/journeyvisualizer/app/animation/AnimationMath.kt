package com.journeyvisualizer.app.animation

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic geographic math for the animation engine (Phase 5).
 *
 * All functions are pure: same inputs → same outputs, no randomness, no
 * clock reads. This is what makes the animation reproducible for a future
 * video renderer.
 */
object AnimationMath {

    /**
     * Spherical linear interpolation (slerp) between two coordinates.
     *
     * Travels the great-circle arc, so long jumps (e.g. Lahore → New York)
     * curve correctly over the globe instead of cutting a straight,
     * visually-wrong line across the map. For short distances it is
     * indistinguishable from linear interpolation. The antimeridian is
     * handled naturally by the vector math.
     *
     * @param fraction 0.0 → [lat1]/[lng1], 1.0 → [lat2]/[lng2].
     */
    fun slerp(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
        fraction: Double,
    ): Pair<Double, Double> {
        val f = fraction.coerceIn(0.0, 1.0)
        if (f <= 0.0) return lat1 to lng1
        if (f >= 1.0) return lat2 to lng2
        val a = toVector(lat1, lng1)
        val b = toVector(lat2, lng2)
        val omega = angleBetween(a, b)
        // Near-identical points: fall back to linear to avoid div-by-zero.
        if (omega < 1e-9) {
            return (lat1 + f * (lat2 - lat1)) to (lng1 + f * (lng2 - lng1))
        }
        val sinOmega = sin(omega)
        val ka = sin((1 - f) * omega) / sinOmega
        val kb = sin(f * omega) / sinOmega
        val x = ka * a[0] + kb * b[0]
        val y = ka * a[1] + kb * b[1]
        val z = ka * a[2] + kb * b[2]
        val lat = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
        val lng = Math.toDegrees(kotlin.math.atan2(y, x))
        return lat to lng
    }

    /** Great-circle distance in meters (haversine). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow2() +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow2()
        return 2 * r * asin(sqrt(a))
    }

    private fun Double.pow2(): Double = this * this

    private fun toVector(lat: Double, lng: Double): DoubleArray {
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lng)
        return doubleArrayOf(
            cos(phi) * cos(lambda),
            cos(phi) * sin(lambda),
            sin(phi),
        )
    }

    private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        // acos is unstable near 0; use atan2 formulation.
        return kotlin.math.atan2(sqrt(1 - dot * dot), dot)
    }
}
