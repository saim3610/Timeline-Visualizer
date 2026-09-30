package com.journeyvisualizer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Poor-man's ANR reporter.
 *
 * [CrashReporter] only sees uncaught JVM exceptions. A frozen UI — the main
 * thread blocked for seconds and then the process killed — leaves no trace,
 * which is exactly the "stuck for a few seconds, then straight to the home
 * screen" symptom with no crash dialog afterwards.
 *
 * This watchdog pings the main thread once a second. If the main thread
 * fails to answer for [BLOCK_THRESHOLD_MS], the main thread's stack trace is
 * written to [ANR_FILE_NAME]. When the main thread becomes responsive again
 * the file is deleted, so a report only survives when the process actually
 * died (or was killed) while blocked — transient jank leaves nothing behind.
 *
 * [MainActivity] picks the report up on the next launch through
 * [CrashReporter.consumeReport], reusing the existing copy dialog.
 *
 * Privacy: a stack trace carries class/method names and line numbers only —
 * never Timeline coordinates, file names, or file contents.
 */
object AnrWatchdog {

    internal const val ANR_FILE_NAME = "anr-report.txt"

    private const val PING_INTERVAL_MS = 1000L
    private const val BLOCK_THRESHOLD_MS = 3000L
    private const val MAX_TRACE_CHARS = 20000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastAnswerMs = AtomicLong(0L)
    private val blockRecorded = AtomicBoolean(false)

    @Volatile
    private var filesDir: File? = null

    @Synchronized
    fun install(app: Context) {
        if (filesDir != null) return
        filesDir = app.filesDir
        lastAnswerMs.set(SystemClock.uptimeMillis())
        val checker = Thread(::watchLoop, "AnrWatchdog")
        checker.isDaemon = true
        checker.start()
    }

    private fun watchLoop() {
        while (true) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            // If the main thread is alive it stamps this almost immediately;
            // if it is blocked, the stamp goes stale and the age grows.
            mainHandler.post { lastAnswerMs.set(SystemClock.uptimeMillis()) }
            val blockedFor = now - lastAnswerMs.get()
            if (blockedFor >= BLOCK_THRESHOLD_MS) {
                if (blockRecorded.compareAndSet(false, true)) {
                    writeAnrReport(blockedFor)
                }
            } else {
                // Responsive again: a transient jank left no trace worth keeping.
                if (blockRecorded.compareAndSet(true, false)) {
                    clearAnrReport()
                }
            }
        }
    }

    private fun writeAnrReport(blockedForMs: Long) {
        val dir = filesDir ?: return
        try {
            val mainThread = Looper.getMainLooper().thread
            val trace = mainThread.stackTrace
                .joinToString("\n") { "    at $it" }
                .take(MAX_TRACE_CHARS)
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(dir, ANR_FILE_NAME).writeText(
                "Timeline Visualizer ANR report\n" +
                    "Detected at: $ts (main thread unresponsive for ${blockedForMs}ms)\n" +
                    "Main thread state: ${mainThread.state}\n" +
                    "Stack trace (most recent call first):\n$trace\n",
            )
        } catch (_: Exception) {
            // Never break the watchdog itself.
        }
    }

    private fun clearAnrReport() {
        try {
            filesDir?.let { File(it, ANR_FILE_NAME).delete() }
        } catch (_: Exception) {
            // Best effort only.
        }
    }
}
