package com.journeyvisualizer.app.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Lightweight post-export validation (Phase 7).
 *
 * A render is only reported COMPLETED after the output passes these
 * checks: the file exists with nonzero size, its container is readable,
 * and the decoded duration/dimensions approximately match the render.
 * Anything else → the file is discarded and the render FAILED.
 */
object OutputValidator {

    data class Info(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    )

    fun validate(
        context: Context,
        uri: Uri,
        expectedDurationMs: Long,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Result<Info> = runCatching {
        val sizeBytes = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize
        } ?: -1L
        if (sizeBytes <= 0) throw RenderException(RenderError.OutputInvalid)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: throw RenderException(RenderError.OutputInvalid)
            val width =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: throw RenderException(RenderError.OutputInvalid)
            val height =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: throw RenderException(RenderError.OutputInvalid)
            // Duration tolerance: ±10% or ±1 s, whichever is larger.
            val toleranceMs = maxOf(1000L, (expectedDurationMs * 0.1).toLong())
            if (kotlin.math.abs(durationMs - expectedDurationMs) > toleranceMs) {
                throw RenderException(RenderError.OutputInvalid)
            }
            if (width != expectedWidth || height != expectedHeight) {
                throw RenderException(RenderError.OutputInvalid)
            }
            Info(durationMs, width, height, sizeBytes)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }
}
