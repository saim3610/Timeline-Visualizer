package com.journeyvisualizer.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class VideoEntry(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val sizeBytes: Long,
)

object VideoRepository {

    const val RELATIVE_DIR = "Movies/Timeline Visualizer"

    fun listVideos(context: Context): List<VideoEntry> {
        val out = ArrayList<VideoEntry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Video.Media.RELATIVE_PATH}=?" to arrayOf("$RELATIVE_DIR/")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/$RELATIVE_DIR/%")
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    VideoEntry(
                        id = id,
                        uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()
                        ),
                        displayName = c.getString(nameCol) ?: "video.mp4",
                        durationMs = c.getLong(durCol),
                        dateAddedSec = c.getLong(dateCol),
                        sizeBytes = c.getLong(sizeCol),
                    )
                )
            }
        }
        return out
    }

    fun delete(context: Context, entry: VideoEntry): Boolean =
        context.contentResolver.delete(entry.uri, null, null) > 0

    /** Creates a pending MediaStore entry and returns its Uri for the exporter to write. */
    fun createPendingVideo(context: Context, displayName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Timeline Visualizer",
            )
            if (!dir.exists()) dir.mkdirs()
            Uri.fromFile(File(dir, displayName))
        }
    }

    fun finishPendingVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(uri.path), arrayOf("video/mp4"), null)
        }
    }

    fun abortPendingVideo(context: Context, uri: Uri) {
        try {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * True when a video with [displayName] already exists in the app's
     * output directory. Used to build collision-free export filenames.
     */
    fun displayNameExists(context: Context, displayName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection =
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.Video.Media.DISPLAY_NAME}=?"
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf("$RELATIVE_DIR/", displayName), null,
            )?.use { it.count > 0 } ?: false
        } else {
            @Suppress("DEPRECATION")
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Timeline Visualizer/$displayName",
            ).exists()
        }
    }
}
