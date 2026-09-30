package com.journeyvisualizer.app

import android.content.Context
import java.io.File

/**
 * Last-resort crash capture.
 *
 * When the process dies from an uncaught exception Android shows nothing
 * the user can share, which makes field crashes undebuggable. This handler
 * writes the stack trace to internal storage before chaining to the
 * previous handler (so the process still dies normally). [MainActivity]
 * picks the report up on the next launch and offers to copy it.
 *
 * Never contains Timeline coordinates: our parse/import code never puts
 * raw JSON or coordinates into exception messages.
 */
object CrashReporter {

    private const val FILE_NAME = "crash-report.txt"
    private const val MAX_CHARS = 24_000

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    appendLine("Timeline Visualizer crash report")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.toString())
                    appendLine(throwable.stackTraceToString().take(MAX_CHARS))
                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && depth < 4) {
                        appendLine("Caused by: $cause")
                        appendLine(cause.stackTraceToString().take(6_000))
                        cause = cause.cause
                        depth++
                    }
                }
                File(app.filesDir, FILE_NAME).writeText(report)
            } catch (_: Exception) {
                // Never break the crash path itself.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Returns the pending crash report and clears it, or null when the
     * previous session did not crash.
     */
    fun consumeReport(app: Context): String? {
        val file = File(app.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}
