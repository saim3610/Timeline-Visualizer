package com.journeyvisualizer.app.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Small cached thumbnails for history cards.
 *
 * Thumbnails are JPEGs in the app's private cache
 * (`cache/video_thumbs/<videoId>.jpg`) — never in MediaStore, never
 * uploaded. Generation pulls one deterministic frame (~1 s in) without
 * playing the video. History never depends on thumbnails: a failed
 * generation just leaves a null path and the card shows a placeholder.
 */
object ThumbnailStore {

    private const val DIR = "video_thumbs"
    private const val MAX_DIM_PX = 480

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun fileFor(context: Context, videoId: String): File =
        File(dir(context), "$videoId.jpg")

    /**
     * Generates and caches a thumbnail for [uri]. Returns the file path,
     * or null when extraction failed. Safe to call repeatedly — an
     * existing thumbnail is reused.
     */
    fun generate(context: Context, uri: Uri, videoId: String): String? {
        val file = fileFor(context, videoId)
        if (file.exists() && file.length() > 0) return file.absolutePath
        val frame: Bitmap = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return null
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            return null
        }
        return try {
            val scaled = scaleDown(frame)
            if (scaled !== frame) frame.recycle()
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaled.recycle()
            if (file.length() > 0) file.absolutePath else null
        } catch (_: Exception) {
            frame.recycle()
            file.delete()
            null
        }
    }

    /** Decodes a cached thumbnail, sampled down for list memory safety. */
    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, opts)
            val sample = sequenceOf(1, 2, 4, 8).firstOrNull { s ->
                opts.outWidth / s <= MAX_DIM_PX && opts.outHeight / s <= MAX_DIM_PX
            } ?: 8
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, videoId: String) {
        runCatching { fileFor(context, videoId).delete() }
    }

    /** Removes thumbnails with no matching history record. */
    fun sweepOrphans(context: Context, knownIds: Set<String>) {
        try {
            dir(context).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".jpg") &&
                    !knownIds.contains(f.nameWithoutExtension)
                ) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val scale = minOf(1f, MAX_DIM_PX / maxOf(w, h).toFloat())
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src, (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), true,
        )
    }
}
