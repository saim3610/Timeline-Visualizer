package com.journeyvisualizer.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches single raster tiles for map-style thumbnails (Phase 4).
 *
 * This shows REAL map data in the style picker — never a decorative fake.
 * Tiles are disk-cached under the app cache dir; failures return null and
 * the UI falls back to a neutral placeholder.
 */
object TileImageFetcher {

    /**
     * Fetch one tile bitmap for [style] at a fixed world location (zoom 2,
     * centered near Europe/Africa so land, sea, and labels are visible).
     * For [BasemapStyle.HYBRID] the label overlay is composited on top.
     */
    suspend fun fetchStyleThumbnail(
        context: Context,
        style: BasemapStyle,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val spec = BasemapStyles.specFor(style)
        // Fixed tile: z=2, x=2, y=1 covers Europe/Africa — good land/sea mix.
        val base = fetchTile(context, spec.base, z = 2, x = 2, y = 1) ?: return@withContext null
        val overlaySpec = spec.overlay ?: return@withContext base
        val overlay = fetchTile(context, overlaySpec, z = 2, x = 2, y = 1)
            ?: return@withContext base
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)
        canvas.drawBitmap(overlay, 0f, 0f, null)
        out
    }

    private fun fetchTile(
        context: Context,
        spec: TileLayerSpec,
        z: Int,
        x: Int,
        y: Int,
    ): Bitmap? {
        val dir = File(context.cacheDir, "style_thumbs").apply { mkdirs() }
        val safe = spec.urlPattern.hashCode().toString(16)
        val file = File(dir, "${safe}_${z}_${x}_${y}.img")
        if (file.exists()) {
            decode(file)?.let { return it }
            file.delete()
        }
        return try {
            val conn = (URL(spec.tileUrl(z, x, y)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "TimelineVisualizer/0.1 (Android)")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val tmp = File(dir, "${safe}_${z}_${x}_${y}.tmp")
                conn.inputStream.use { ins ->
                    tmp.outputStream().use { outs -> ins.copyTo(outs) }
                }
                tmp.renameTo(file)
            } finally {
                conn.disconnect()
            }
            decode(file)
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(f: File): Bitmap? = try {
        BitmapFactory.decodeFile(f.absolutePath)
    } catch (_: Exception) {
        null
    }
}
