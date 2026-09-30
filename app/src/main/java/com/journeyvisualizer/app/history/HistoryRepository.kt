package com.journeyvisualizer.app.history

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.journeyvisualizer.app.data.VideoRepository
import com.journeyvisualizer.app.export.ExportProgressBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Owns the history data flow: Room for metadata, MediaStore for the actual
 * MP4, app cache for thumbnails.
 *
 * Rules enforced here:
 * - The video file is the source of truth for availability; the database
 *   never overrules it.
 * - Delete removes the file first; the record is only dropped when the
 *   file is gone (or was already gone).
 * - Rename never breaks the content URI: if the MediaStore rename fails,
 *   the display name still updates in the database and the URI stays valid.
 * - All work runs off the main thread.
 */
class HistoryRepository(
    private val appContext: Context,
    private val dao: VideoHistoryDao = HistoryDatabase.get(appContext).videoHistoryDao(),
) {

    fun observeAll(): Flow<List<VideoHistoryItem>> =
        dao.observeAll().map { rows -> rows.map { it.toItem() } }

    fun observeById(id: String): Flow<VideoHistoryItem?> =
        dao.observeById(id).map { it?.toItem() }

    suspend fun getById(id: String): VideoHistoryItem? = withContext(Dispatchers.IO) {
        dao.getVideoById(id)?.toItem()
    }

    suspend fun getAll(): List<VideoHistoryItem> = withContext(Dispatchers.IO) {
        dao.getAllVideos().map { it.toItem() }
    }

    /** Registers a freshly finalized export. Only called after verification. */
    suspend fun insert(item: VideoHistoryItem) = withContext(Dispatchers.IO) {
        dao.insertVideo(VideoHistoryEntity.fromItem(item))
    }

    suspend fun setThumbnailPath(id: String, path: String?) =
        withContext(Dispatchers.IO) { dao.setThumbnailPath(id, path) }

    /**
     * Renames a video's display name. Tries to rename the underlying
     * MediaStore file too (app-owned files can be renamed on API 29+);
     * if that fails the URI stays stable and only the display name changes.
     */
    suspend fun rename(id: String, newName: String): RenameValidator.Result =
        withContext(Dispatchers.IO) {
            val entity = dao.getVideoById(id)
                ?: return@withContext RenameValidator.Result.Invalid("Video not found.")
            val others = dao.getAllVideos()
                .filter { it.id != id }
                .map { it.displayName }
            when (val v = RenameValidator.validate(newName, others)) {
                is RenameValidator.Result.Ok -> Unit
                else -> return@withContext v
            }
            val trimmed = newName.trim()
            renameMediaStoreFile(entity, trimmed)
            dao.renameVideo(id, trimmed, System.currentTimeMillis())
            RenameValidator.Result.Ok
        }

    private fun renameMediaStoreFile(entity: VideoHistoryEntity, displayName: String) {
        try {
            val uri = Uri.parse(entity.contentUri)
            if (uri.scheme != "content") return
            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    RenameValidator.toSafeFileName(displayName),
                )
            }
            appContext.contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {
            // URI stays stable; the display name still updates in the DB.
        }
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class FileDeleteFailed(val message: String) : DeleteResult
    }

    /**
     * Deletes the actual video, its thumbnail, then its metadata — in that
     * order. If the file is already gone, the record is still cleaned up.
     */
    suspend fun delete(id: String): DeleteResult = withContext(Dispatchers.IO) {
        val entity = dao.getVideoById(id)
            ?: return@withContext DeleteResult.Deleted
        val uri = runCatching { Uri.parse(entity.contentUri) }.getOrNull()
        val fileGone: Boolean = if (uri == null) {
            true
        } else {
            try {
                val rows = if (uri.scheme == "file") {
                    if (java.io.File(uri.path ?: "").delete()) 1 else 0
                } else {
                    appContext.contentResolver.delete(uri, null, null)
                }
                rows > 0 || !isReachable(uri)
            } catch (_: Exception) {
                !isReachable(uri)
            }
        }
        if (!fileGone) {
            return@withContext DeleteResult.FileDeleteFailed(
                "Could not delete the video file. The entry was kept.",
            )
        }
        entity.thumbnailPath?.let { java.io.File(it).delete() }
        ThumbnailStore.delete(appContext, entity.id)
        dao.deleteVideo(id)
        DeleteResult.Deleted
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        val all = dao.getAllVideos()
        var removed = 0
        for (entity in all) {
            if (delete(entity.id) is DeleteResult.Deleted) removed++
        }
        removed
    }

    /**
     * Orphan detection. Runs when history loads: compares the records
     * against what MediaStore actually has in our output directory and
     * drops records whose video no longer exists (e.g. deleted in the
     * Gallery). Also sweeps pending export leftovers from crashed renders.
     *
     * Never crashes; never touches files outside our directory.
     */
    suspend fun refreshAvailability(): Int = withContext(Dispatchers.IO) {
        val live = try {
            VideoRepository.listVideos(appContext)
        } catch (_: Exception) {
            return@withContext 0
        }
        val liveIds = live.mapNotNullTo(HashSet()) { entry ->
            entry.uri.toString()
        }
        val liveMediaIds = live.mapTo(HashSet()) { it.id }
        val records = dao.getAllVideos()
        var cleaned = 0
        for (record in records) {
            val uri = record.contentUri
            val reachable = when {
                liveIds.contains(uri) -> true
                record.mediaStoreId != null && liveMediaIds.contains(record.mediaStoreId) -> true
                else -> isReachable(runCatching { Uri.parse(uri) }.getOrNull())
            }
            if (!reachable) {
                record.thumbnailPath?.let { java.io.File(it).delete() }
                ThumbnailStore.delete(appContext, record.id)
                dao.deleteVideo(record.id)
                cleaned++
            }
        }
        // Never sweep while a render is in flight: its pending entry has no
        // history record yet, and deleting it mid-export would corrupt the
        // render. ExportProgressBus.isActive() covers the whole job lifetime.
        if (!ExportProgressBus.isActive()) {
            sweepPendingLeftovers(records)
        }
        cleaned
    }

    /**
     * Removes IS_PENDING leftovers in our directory that have no history
     * record (crashed or killed exports). Only our own directory is scanned.
     */
    private fun sweepPendingLeftovers(records: List<VideoHistoryEntity>) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.IS_PENDING,
            )
            val selection =
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.Video.Media.IS_PENDING}=1"
            val known = records.map { it.contentUri }.toSet()
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection,
                arrayOf("${VideoRepository.RELATIVE_DIR}/"),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (c.moveToNext()) {
                    val stale = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol).toString(),
                    )
                    if (!known.contains(stale.toString())) {
                        runCatching {
                            appContext.contentResolver.delete(stale, null, null)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isReachable(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            if (uri.scheme == "file") {
                java.io.File(uri.path ?: return false).exists()
            } else {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }
}
