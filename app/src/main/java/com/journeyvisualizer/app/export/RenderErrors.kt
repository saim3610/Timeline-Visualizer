package com.journeyvisualizer.app.export

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Export failure taxonomy (Phase 7). Pure Kotlin.
 *
 * Every failure the renderer can produce maps to one of these, each
 * carrying a human-readable message safe for the UI. Raw stack traces
 * never reach the user; technical detail stays in logcat.
 */
sealed interface RenderError {
    /** Safe to display in the UI. */
    val userMessage: String

    data class EncoderUnavailable(
        val detail: String = "",
    ) : RenderError {
        override val userMessage: String =
            "This device has no video encoder available, so the video could not be created."
    }

    data class UnsupportedConfig(
        val detail: String = "",
        /** e.g. "Try 1080p instead." — null when there is no useful fallback. */
        val suggestion: String? = null,
    ) : RenderError {
        override val userMessage: String = buildString {
            append("This export setting is not supported on this device.")
            if (suggestion != null) append(" $suggestion")
        }
    }

    data class FourKUnsupported(
        val detail: String = "",
    ) : RenderError {
        override val userMessage: String = "4K export is not supported on this device."
    }

    data class TilesUnavailable(
        val missingTiles: Int = 0,
    ) : RenderError {
        override val userMessage: String =
            "The map could not be loaded reliably (offline or tiles unavailable). " +
                "Connect to the internet and try again — no video was created."
    }

    data object OutOfMemory : RenderError {
        override val userMessage: String =
            "The device ran out of memory while rendering. Try a lower resolution."
    }

    data object StorageFailure : RenderError {
        override val userMessage: String =
            "The video could not be saved. Check free storage space and try again."
    }

    data object InvalidComposition : RenderError {
        override val userMessage: String =
            "The video settings are invalid. Adjust them and try again."
    }

    data object EmptyTimeline : RenderError {
        override val userMessage: String =
            "There is no journey to export. Import a timeline first."
    }

    data object OutputInvalid : RenderError {
        override val userMessage: String =
            "The video file failed verification and was discarded. Please try again."
    }

    data object Interrupted : RenderError {
        override val userMessage: String =
            "Export was interrupted before it could finish."
    }

    data class Unknown(
        val detail: String = "",
    ) : RenderError {
        override val userMessage: String =
            "Export failed unexpectedly. Please try again."
    }
}

/**
 * Maps a thrown [Throwable] to the closest [RenderError].
 * [CancellationException] is never mapped — callers handle cancellation
 * as a state, not a failure.
 */
fun mapToRenderError(t: Throwable): RenderError {
    if (t is RenderException) return t.error
    return when (t) {
        is OutOfMemoryError -> RenderError.OutOfMemory
        is IOException -> RenderError.StorageFailure
        is IllegalArgumentException -> RenderError.InvalidComposition
        is IllegalStateException ->
            if (t.message?.contains("codec", ignoreCase = true) == true ||
                t.message?.contains("encoder", ignoreCase = true) == true
            ) {
                RenderError.EncoderUnavailable(t.message ?: "")
            } else {
                RenderError.Unknown(t.message ?: t.javaClass.simpleName)
            }
        else -> RenderError.Unknown(t.message ?: t.javaClass.simpleName)
    }
}

/** A failure the renderer raises deliberately, already classified. */
class RenderException(val error: RenderError, cause: Throwable? = null) :
    Exception(error.userMessage, cause)
