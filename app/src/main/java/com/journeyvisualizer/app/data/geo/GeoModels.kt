package com.journeyvisualizer.app.data.geo

/**
 * One row of the bundled GeoNames dataset (`assets/geonames/cities.tsv`).
 *
 * The dataset really contains five columns — name, latitude, longitude,
 * country name, population — and nothing else. There are no region/admin
 * codes, no country codes, no feature classes and no GeoName IDs, so this
 * model deliberately exposes only what the data can provide. Anything
 * fancier would be invented, not resolved.
 */
data class GeoCity(
    val name: String,
    /** Full country name as shipped in the dataset, e.g. "Pakistan". */
    val country: String,
    val lat: Double,
    val lng: Double,
    val population: Long,
)

/**
 * How well a [ResolvedLocation] represents the original coordinate.
 *
 * Never present a distant GeoNames point as an exact user location: matches
 * beyond [LocationResolutionConfig.closeMatchKm] are flagged APPROXIMATE,
 * and anything beyond [LocationResolutionConfig.maxMatchKm] is UNRESOLVED.
 */
enum class MatchQuality {
    /** Within [LocationResolutionConfig.closeMatchKm] of the coordinate. */
    CLOSE,

    /** A real nearby place, but far enough that it is only indicative. */
    APPROXIMATE,

    /** No GeoNames place within range, or the coordinate was invalid. */
    UNRESOLVED,
}

/**
 * Geographic metadata resolved for one Timeline coordinate.
 *
 * This is metadata *about* the point, never a replacement for it: the raw
 * latitude/longitude in the Timeline data are always kept alongside this
 * (see [ResolvedPoint]), because the future map engine needs exact
 * coordinates for route rendering and animation.
 */
data class ResolvedLocation(
    /** The exact Timeline coordinate that was resolved (unrounded). */
    val latitude: Double,
    val longitude: Double,
    /** Resolved place name, e.g. "Lahore". Null when [isResolved] is false. */
    val name: String?,
    /** Resolved country name, e.g. "Pakistan". Null when unavailable. */
    val country: String?,
    /** Great-circle distance from the coordinate to the GeoNames point, in km. */
    val distanceKm: Double,
    val matchQuality: MatchQuality,
) {
    val isResolved: Boolean get() = matchQuality != MatchQuality.UNRESOLVED
}

/**
 * One Timeline point paired with its resolved location metadata.
 *
 * Raw Timeline data + resolved metadata, side by side. The [point] keeps
 * the exact original coordinates and timestamp; [location] carries the
 * human-readable geography. This is the unit the future map engine will
 * consume: `TimelinePoint -> (latitude, longitude, resolvedLocation)`.
 */
data class ResolvedPoint(
    val point: com.journeyvisualizer.app.data.model.TrackPoint,
    val location: ResolvedLocation,
)

/** Builds an UNRESOLVED location for a coordinate (invalid input or missing dataset). */
fun unresolvedLocation(lat: Double, lng: Double): ResolvedLocation =
    ResolvedLocation(lat, lng, null, null, Double.NaN, MatchQuality.UNRESOLVED)

/**
 * Centralized tuning for the location-resolution engine (§9 of the plan).
 *
 * Thresholds were chosen against the actual bundled dataset (~34k places,
 * roughly cities of 15k+ inhabitants):
 * - In populated regions the nearest such place is usually within a few km,
 *   so 25 km is a generous "close" boundary that still rejects rural
 *   mismatches.
 * - 100 km is the outer "we can name *something* nearby" boundary; beyond
 *   it (oceans, deserts, poles) we say "unavailable" instead of guessing.
 * Both are configurable in one place rather than hardcoded at call sites.
 */
data class LocationResolutionConfig(
    /** At or below this distance a match counts as CLOSE. */
    val closeMatchKm: Double = 25.0,
    /** Beyond this distance a coordinate is UNRESOLVED. */
    val maxMatchKm: Double = 100.0,
    /**
     * Population tie-breaker window: when several places fall within this
     * distance of the *nearest* one, the most populous wins (disambiguates
     * duplicate metro entries). Kept deliberately small so distance stays
     * the primary factor — a town 2 km away always beats a city 25 km away.
     */
    val tieBreakerEpsilonKm: Double = 2.0,
    /** Grid cell size in degrees for the spatial index. */
    val gridCellDegrees: Double = 1.0,
    /** Decimals used for cache/dedup keys (≈111 m at 3 decimals). */
    val cacheKeyDecimals: Int = 3,
    /** Max entries in the in-memory resolution cache. */
    val cacheMaxEntries: Int = 20_000,
)
