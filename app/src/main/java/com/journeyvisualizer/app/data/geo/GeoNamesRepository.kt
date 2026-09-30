package com.journeyvisualizer.app.data.geo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader

/**
 * Loads and holds the bundled GeoNames dataset for the whole app session.
 *
 * - The TSV at `assets/geonames/cities.tsv` is read and indexed **once**;
 *   every later lookup reuses the same in-memory data.
 * - Loading is lazy: nothing happens at app startup. The first import (or
 *   any feature needing locations) triggers it on a background thread.
 * - All processing is local — coordinates are never uploaded, sent to an
 *   API, or logged.
 *
 * The legacy [CityDatabase] (used by the pre-existing export screens) is
 * intentionally left untouched; this repository is the Phase 3 engine that
 * the new Timeline UI and the future map engine build on.
 */
class GeoNamesRepository private constructor(
    val cities: List<GeoCity>,
    val index: GeoGridIndex,
    val config: LocationResolutionConfig,
) {
    val isEmpty: Boolean get() = cities.isEmpty()

    companion object {
        private const val ASSET_PATH = "geonames/cities.tsv"

        @Volatile
        private var instance: GeoNamesRepository? = null
        private val mutex = Mutex()

        /**
         * Returns the session singleton, loading and indexing the dataset on
         * first use. Safe to call from any thread; concurrent callers share
         * one load.
         *
         * @throws GeoNamesLoadException if the asset is missing or unreadable.
         */
        suspend fun get(
            context: Context,
            config: LocationResolutionConfig = LocationResolutionConfig(),
        ): GeoNamesRepository = mutex.withLock {
            instance?.let { return it }
            val loaded = load(context.applicationContext, config)
            instance = loaded
            loaded
        }

        /** The loaded singleton, or null if [get] has not run yet. */
        fun peek(): GeoNamesRepository? = instance

        /** Visible for tests: builds a repository from any [Reader]. */
        fun create(reader: Reader, config: LocationResolutionConfig = LocationResolutionConfig()): GeoNamesRepository {
            val cities = parseTsv(reader)
            return GeoNamesRepository(cities, GeoGridIndex(cities, config.gridCellDegrees), config)
        }

        private suspend fun load(
            appContext: Context,
            config: LocationResolutionConfig,
        ): GeoNamesRepository = withContext(Dispatchers.IO) {
            try {
                appContext.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                    create(reader, config)
                }
            } catch (e: Exception) {
                throw GeoNamesLoadException(
                    "The offline location data could not be loaded.", e
                )
            }
        }

        /**
         * Parses the real five-column TSV: name, latitude, longitude,
         * country name, population. Malformed rows are skipped; an empty
         * result is returned (not thrown) so callers degrade gracefully.
         */
        private fun parseTsv(reader: Reader): List<GeoCity> {
            val list = ArrayList<GeoCity>(35_000)
            reader.buffered().forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEachLine
                val name = p[0].trim()
                if (name.isEmpty()) return@forEachLine
                val lat = p[1].toDoubleOrNull() ?: return@forEachLine
                val lng = p[2].toDoubleOrNull() ?: return@forEachLine
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@forEachLine
                val country = p[3].trim().ifEmpty { return@forEachLine }
                val pop = p[4].toLongOrNull() ?: 0L
                list.add(GeoCity(name, country, lat, lng, pop))
            }
            return list
        }
    }
}

/** The bundled location data could not be read. Callers should degrade to unresolved locations. */
class GeoNamesLoadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
