package com.journeyvisualizer.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.journeyvisualizer.app.map.TileKey
import com.journeyvisualizer.app.map.TileLayerSpec
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Style-aware tile fetcher for the Phase 7 export renderer.
 *
 * Unlike the legacy [com.journeyvisualizer.app.map.TileCache] (hard-coded
 * CARTO), this cache is parameterized by the map style's [TileLayerSpec]s —
 * the base layer plus an optional overlay layer (e.g. Esri labels over
 * Esri imagery for HYBRID). Keyed by [TileKey] so the prefetcher and the
 * frame renderer address exactly the same tiles.
 *
 * Blocking: call only from a background thread. Memory + disk cached.
 * Network failures return null — they are counted by [prefetch] and never
 * thrown, so one bad tile cannot abort the whole preflight.
 */
class StyleTileCache(
    context: Context,
    private val layers: List<TileLayerSpec>,
) {
    private val appContext = context.applicationContext
    private val cacheDir: File = File(appContext.cacheDir, "style_tiles_${cacheTag()}").also {
        it.mkdirs()
    }

    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    data class PrefetchResult(
        val requested: Int,
        val succeeded: Int,
        val failedKeys: Set<TileKey>,
        /**
         * Tiles whose BASE layer is missing — these render as holes in the
         * map. A missing overlay layer only loses labels, so the
         * missing-tile policy is evaluated against this set.
         */
        val failedBaseKeys: Set<TileKey>,
    )

    /** Memory + disk lookup only — no network. Used during encoding. */
    private fun getCached(key: TileKey, layer: TileLayerSpec): Bitmap? {
        val mk = memKey(key, layer)
        return memoryCache.get(mk) ?: readDisk(key, layer)?.also { memoryCache.put(mk, it) }
    }

    /**
     * Fetch one bitmap per (tile, layer). The renderer composites layers
     * itself, so a tile is "ready" only when every layer fetched.
     */
    fun getTileLayers(key: TileKey): List<Bitmap?> =
        layers.map { layer ->
            getCached(key, layer)
                ?: fetchLayer(key, layer)?.also {
                    memoryCache.put(memKey(key, layer), it)
                    writeDisk(key, layer, it)
                }
        }

    /** Layers from memory/disk only — used while encoding (no network). */
    fun getCachedLayers(key: TileKey): List<Bitmap?> =
        layers.map { layer -> getCached(key, layer) }

    /**
     * Blocking prefetch of every tile the render will need. Reports real
     * progress and returns the exact set of tiles that failed, so the
     * caller can decide whether coverage is good enough to encode.
     */
    fun prefetch(
        keys: Set<TileKey>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): PrefetchResult {
        val failed = LinkedHashSet<TileKey>()
        val failedBase = LinkedHashSet<TileKey>()
        var done = 0
        for (key in keys) {
            val layers = getTileLayers(key)
            if (layers.any { it == null }) failed.add(key)
            if (layers.firstOrNull() == null) failedBase.add(key)
            done++
            if (done % 8 == 0 || done == keys.size) onProgress(done, keys.size)
        }
        return PrefetchResult(keys.size, keys.size - failed.size, failed, failedBase)
    }

    fun clearMemory() = memoryCache.evictAll()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun cacheTag(): String = layers.joinToString("+") { it.urlPattern }.hashCode()
        .toString(16).replace('-', 'n')

    private fun memKey(key: TileKey, layer: TileLayerSpec): String =
        "${layer.urlPattern.hashCode()}/${key.z}/${key.x}/${key.y}"

    private fun diskFile(key: TileKey, layer: TileLayerSpec): File =
        File(cacheDir, "${layer.urlPattern.hashCode().toString(16)}_${key.z}_${key.x}_${key.y}.png")

    private fun readDisk(key: TileKey, layer: TileLayerSpec): Bitmap? {
        val f = diskFile(key, layer)
        if (!f.exists()) return null
        return try {
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                .let { BitmapFactory.decodeFile(f.absolutePath, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(key: TileKey, layer: TileLayerSpec, bmp: Bitmap) {
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, diskFile(key, layer).outputStream())
        } catch (_: Exception) {
            // Disk cache is best-effort; the memory cache still serves.
        }
    }

    private fun fetchLayer(key: TileKey, layer: TileLayerSpec): Bitmap? {
        if (key.z !in layer.minZoom..layer.maxZoom) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(layer.tileUrl(key.z, key.x, key.y)).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    .let { BitmapFactory.decodeStream(input, null, it) }
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val MEMORY_CACHE_KB = 32 * 1024
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
        private const val USER_AGENT = "TimelineVisualizer/1.0 (Android export renderer)"
    }
}
