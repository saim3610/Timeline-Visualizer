package com.journeyvisualizer.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Raster basemap tiles (CARTO Voyager, OSM data) with memory + disk caching.
 * The only network feature of the app: your Timeline data is never uploaded.
 */
class TileCache(context: Context) {

    private val mem = object : LruCache<TileKey, Bitmap>(64) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount / 1024
    }
    private val dir = File(context.cacheDir, "tiles").apply { mkdirs() }

    /**
     * Blocking fetch; safe to call from any background thread.
     * [x] is wrapped around the date line; out-of-range [y] returns null.
     */
    fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        val wx = ((x % n) + n) % n
        val key = TileKey(z, wx, y)

        synchronized(mem) { mem.get(key)?.let { return it } }

        val file = File(dir, "${z}_${wx}_${y}.png")
        if (file.exists()) {
            decode(file)?.let { bmp ->
                synchronized(mem) { mem.put(key, bmp) }
                return bmp
            }
            file.delete()
        }

        return try {
            val url = URL("https://basemaps.cartocdn.com/rastertiles/voyager/$z/$wx/$y.png")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "TimelineVisualizer/0.1 (Android)")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val tmp = File(dir, "${z}_${wx}_${y}.tmp")
                conn.inputStream.use { ins ->
                    tmp.outputStream().use { outs -> ins.copyTo(outs) }
                }
                tmp.renameTo(file)
            } finally {
                conn.disconnect()
            }
            decode(file)?.also { bmp ->
                synchronized(mem) { mem.put(key, bmp) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(f: File): Bitmap? =
        try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }

    fun clearMemory() {
        synchronized(mem) { mem.evictAll() }
    }
}
