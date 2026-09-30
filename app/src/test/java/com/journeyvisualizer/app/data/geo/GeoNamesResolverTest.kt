package com.journeyvisualizer.app.data.geo

import com.journeyvisualizer.app.data.model.TrackPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * JVM unit tests for the Phase 3 offline location-resolution engine.
 *
 * Most tests run against small synthetic TSV datasets so the algorithm,
 * indexing, cache and batch behavior are verified deterministically.
 * A second group loads the REAL bundled `assets/geonames/cities.tsv`
 * (when the working directory is the `app/` module dir, as with AGP unit
 * tests) and checks worldwide resolution across 13 countries.
 */
class GeoNamesResolverTest {

    private fun repoOf(tsv: String, config: LocationResolutionConfig = LocationResolutionConfig()) =
        GeoNamesRepository.create(StringReader(tsv), config)

    private fun resolverOf(
        tsv: String,
        config: LocationResolutionConfig = LocationResolutionConfig(),
    ) = LocationResolver(repoOf(tsv, config), config)

    // -- Exact / near match --------------------------------------------------

    @Test
    fun nearCoordinate_resolvesToNearestCity() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        // ~1.5 km from the dataset point.
        val loc = r.resolve(31.5600, 74.3550)
        assertTrue(loc.isResolved)
        assertEquals(MatchQuality.CLOSE, loc.matchQuality)
        assertEquals("Lahore", loc.name)
        assertEquals("Pakistan", loc.country)
        assertTrue("distance ${loc.distanceKm}", loc.distanceKm < 5.0)
        // Query coordinates are preserved exactly, not rounded.
        assertEquals(31.5600, loc.latitude, 0.0)
        assertEquals(74.3550, loc.longitude, 0.0)
    }

    @Test
    fun distantCoordinate_isApproximate_notExact() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        // ~50 km away: a real nearby place, but must not claim to be exact.
        val loc = r.resolve(32.0000, 74.3436)
        assertTrue(loc.isResolved)
        assertEquals(MatchQuality.APPROXIMATE, loc.matchQuality)
        assertEquals("Lahore", loc.name)
        assertTrue("distance ${loc.distanceKm}", loc.distanceKm in 40.0..65.0)
    }

    @Test
    fun farCoordinate_isUnresolved() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        // ~111 km away: beyond maxMatchKm.
        val loc = r.resolve(32.5497, 74.3436)
        assertFalse(loc.isResolved)
        assertEquals(MatchQuality.UNRESOLVED, loc.matchQuality)
        assertNull(loc.name)
        assertNull(loc.country)
    }

    // -- Distance beats population (§8 of the plan) --------------------------

    @Test
    fun distanceIsPrimary_populationOnlyBreaksNearTies() {
        val tsv =
            "TownA\t0.0\t0.0\tNowhere\t50000\n" +
                "CityB\t0.0\t0.225\tNowhere\t10000000\n"
        val r = resolverOf(tsv)
        // ~2 km from TownA, ~25 km from CityB: must pick TownA despite CityB's size.
        val loc = r.resolve(0.018, 0.0)
        assertEquals("TownA", loc.name)
        assertEquals(MatchQuality.CLOSE, loc.matchQuality)
    }

    @Test
    fun populationBreaksNearTies() {
        val tsv =
            "Smallville\t0.0\t0.0\tNowhere\t100000\n" +
                "Bigtown\t0.0\t0.008\tNowhere\t5000000\n"
        val r = resolverOf(tsv)
        // 0.22 km from Smallville, 0.67 km from Bigtown: within the 2 km
        // tie-breaker window, so the larger place wins.
        val loc = r.resolve(0.002, 0.0)
        assertEquals("Bigtown", loc.name)
    }

    // -- Spatial index edges -------------------------------------------------

    @Test
    fun antimeridian_wrapsCorrectly() {
        // City just east of +180°, query just west of -180° (~22 km apart
        // across the line).
        val r = resolverOf("Edgeville\t0.0\t179.9\tNowhere\t100000\n")
        val loc = r.resolve(0.0, -179.9)
        assertTrue(loc.isResolved)
        assertEquals(MatchQuality.CLOSE, loc.matchQuality)
        assertEquals("Edgeville", loc.name)
    }

    @Test
    fun highLatitude_widensLongitudeSearch() {
        val r = resolverOf("Tromso\t69.6496\t18.9560\tNorway\t68300\n")
        // 0.5° of longitude at 69°N is ~19 km; a naive 1°-cell scan in both
        // axes would still find it, but this guards the cos() widening logic.
        val loc = r.resolve(69.6496, 19.4560)
        assertTrue(loc.isResolved)
        assertEquals("Tromso", loc.name)
    }

    // -- Invalid input ---------------------------------------------------------

    @Test
    fun invalidCoordinates_areUnresolved_notCrashes() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        for ((lat, lng) in listOf(
            91.0 to 0.0, -91.0 to 0.0, 0.0 to 181.0, 0.0 to -181.0,
            Double.NaN to 0.0, 0.0 to Double.NaN,
        )) {
            val loc = r.resolve(lat, lng)
            assertFalse("($lat, $lng)", loc.isResolved)
            assertEquals(MatchQuality.UNRESOLVED, loc.matchQuality)
        }
    }

    @Test
    fun emptyDataset_resolvesNothing_gracefully() {
        val r = resolverOf("")
        val loc = r.resolve(31.5497, 74.3436)
        assertFalse(loc.isResolved)
        assertEquals(MatchQuality.UNRESOLVED, loc.matchQuality)
    }

    @Test
    fun malformedRows_areSkipped() {
        val tsv =
            "not a row\n" +
                "Bad\tnope\t74.3\tPakistan\t100\n" +
                "AlsoBad\t31.5\t74.3\tPakistan\n" +
                "Lahore\t31.5497\t74.3436\tPakistan\t11000000\n"
        val repo = repoOf(tsv)
        assertEquals(1, repo.cities.size)
        assertEquals("Lahore", LocationResolver(repo).resolve(31.55, 74.34).name)
    }

    // -- Cache -----------------------------------------------------------------

    @Test
    fun repeatedCoordinate_usesCache() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        r.resolve(31.5600, 74.3550)
        r.resolve(31.5600, 74.3550)
        r.resolve(31.5600, 74.3550)
        assertEquals(1, r.lookups)
        assertEquals(2, r.cacheHits)
        assertEquals(1, r.cacheMisses)
    }

    @Test
    fun cacheRounding_doesNotDegradeAccuracy() {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        // Two distinct coordinates sharing one cache key (3 decimals).
        val a = r.resolve(31.56001, 74.35501)
        val b = r.resolve(31.56004, 74.35504)
        assertEquals(1, r.lookups) // one index scan
        assertEquals(31.56004, b.latitude, 0.0) // exact query preserved
        assertTrue(b.distanceKm != a.distanceKm) // distance recomputed
    }

    // -- Batch -----------------------------------------------------------------

    @Test
    fun batch_dedupesPreservesOrder_reportsProgress() = runBlocking {
        val tsv =
            "Lahore\t31.5497\t74.3436\tPakistan\t11000000\n" +
                "Paris\t48.8566\t2.3522\tFrance\t11000000\n"
        val r = resolverOf(tsv)
        // 2,000 points, only 2 unique coordinates.
        val points = List(2000) { i ->
            if (i % 2 == 0) TrackPoint(31.56, 74.35, 1_700_000_000_000L + i)
            else TrackPoint(48.86, 2.35, 1_700_000_000_000L + i)
        }
        var progressCalls = 0
        var lastDone = 0
        var lastTotal = 0
        val resolved = r.resolveAll(points) { done, total ->
            progressCalls++
            lastDone = done
            lastTotal = total
        }
        assertEquals(2000, resolved.size)
        // Order + raw data preserved.
        assertEquals(1_700_000_000_007L, resolved[7].point.timeMs)
        assertEquals(48.86, resolved[7].point.lat, 0.0)
        // Names attached.
        assertEquals("Lahore", resolved[0].location.name)
        assertEquals("Paris", resolved[1].location.name)
        // One index lookup per unique coordinate, not per point.
        assertEquals(2, r.lookups)
        assertTrue("progress calls: $progressCalls", progressCalls > 0)
        assertEquals(2, lastTotal)
        assertEquals(2, lastDone)
    }

    @Test
    fun batch_emptyInput_returnsEmpty() = runBlocking {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        assertTrue(r.resolveAll(emptyList()).isEmpty())
    }

    @Test
    fun batch_keepsRawCoordinates_intact() = runBlocking {
        val r = resolverOf("Lahore\t31.5497\t74.3436\tPakistan\t11000000\n")
        val points = listOf(TrackPoint(31.5600123, 74.3550456, 123L))
        val resolved = r.resolveAll(points)
        // Raw coordinate untouched by resolution or cache rounding.
        assertEquals(31.5600123, resolved[0].point.lat, 0.0)
        assertEquals(74.3550456, resolved[0].point.lng, 0.0)
        assertEquals(31.5600123, resolved[0].location.latitude, 0.0)
    }

    // -- Real bundled dataset --------------------------------------------------

    private fun realRepo(): GeoNamesRepository {
        val f = File("src/main/assets/geonames/cities.tsv")
        assumeTrue("bundled cities.tsv not found at ${f.absolutePath}", f.exists())
        val t0 = System.nanoTime()
        val repo = GeoNamesRepository.create(f.bufferedReader())
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("cities.tsv: ${repo.cities.size} rows parsed+indexed in ${ms}ms")
        return repo
    }

    @Test
    fun realDataset_hasExpectedScale() {
        val repo = realRepo()
        assertTrue("rows: ${repo.cities.size}", repo.cities.size > 30_000)
        assertTrue(repo.cities.all { it.name.isNotBlank() && it.country.isNotBlank() })
    }

    @Test
    fun realDataset_resolvesWorldwide() {
        val resolver = LocationResolver(realRepo())
        // Well-known public coordinates: (lat, lng) -> expected country.
        val cases = listOf(
            Triple(31.5204, 74.3587, "Pakistan"), // Lahore
            Triple(28.6139, 77.2090, "India"), // New Delhi
            Triple(25.2048, 55.2708, "United Arab Emirates"), // Dubai
            Triple(24.7136, 46.6753, "Saudi Arabia"), // Riyadh
            Triple(51.5074, -0.1278, "United Kingdom"), // London
            Triple(48.8566, 2.3522, "France"), // Paris
            Triple(52.5200, 13.4050, "Germany"), // Berlin
            Triple(40.7128, -74.0060, "United States"), // New York
            Triple(43.6532, -79.3832, "Canada"), // Toronto
            Triple(-33.8688, 151.2093, "Australia"), // Sydney
            Triple(35.6762, 139.6503, "Japan"), // Tokyo
            Triple(-33.9249, 18.4241, "South Africa"), // Cape Town
            Triple(-23.5558, -46.6396, "Brazil"), // São Paulo
        )
        for ((lat, lng, country) in cases) {
            val loc = resolver.resolve(lat, lng)
            assertTrue("($lat, $lng) -> $loc", loc.isResolved)
            assertEquals("($lat, $lng)", MatchQuality.CLOSE, loc.matchQuality)
            assertEquals("($lat, $lng)", country, loc.country)
            assertNotNull("($lat, $lng)", loc.name)
        }
    }

    @Test
    fun realDataset_midOcean_isUnresolved() {
        val resolver = LocationResolver(realRepo())
        // Mid-Pacific: no city within 100 km.
        val loc = resolver.resolve(0.0, -140.0)
        assertFalse(loc.isResolved)
        assertEquals(MatchQuality.UNRESOLVED, loc.matchQuality)
    }

    @Test
    fun realDataset_lookupPerformance() {
        val resolver = LocationResolver(realRepo())
        val queries = listOf(
            31.5204 to 74.3587, 48.8566 to 2.3522, 40.7128 to -74.0060,
            -33.8688 to 151.2093, 35.6762 to 139.6503, 0.0 to -140.0,
        )
        val t0 = System.nanoTime()
        repeat(200) { queries.forEach { (la, ln) -> resolver.resolve(la, ln) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("1200 lookups (6 unique, cached) in ${ms}ms; lookups=${resolver.lookups}")
        assertTrue("1200 lookups took ${ms}ms", ms < 10_000)
    }
}
