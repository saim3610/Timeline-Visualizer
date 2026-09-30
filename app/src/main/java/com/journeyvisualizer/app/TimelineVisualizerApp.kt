package com.journeyvisualizer.app

import android.app.Application
import java.io.File
import org.osmdroid.config.Configuration

/**
 * Installs the crash reporter and the ANR watchdog before anything else runs,
 * so even an early startup crash — or a main-thread freeze that gets the
 * process killed — leaves a report the next launch can show. Also points
 * osmdroid's tile cache at internal storage up front (see below).
 */
class TimelineVisualizerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        AnrWatchdog.install(this)
        preconfigureOsmdroid()
    }

    /**
     * Point osmdroid's tile cache at internal storage before the first MapView
     * is ever constructed. Otherwise the MapView constructor scans every
     * storage volume on the calling (main) thread looking for the "best"
     * writable location — on devices with slow or unhealthy external storage
     * that scan can freeze the UI for seconds before the map ever appears.
     * Pre-setting the paths makes that discovery a no-op. Internal storage
     * also keeps the tile cache private to the app (no extra permissions,
     * no scoped-storage surprises).
     */
    private fun preconfigureOsmdroid() {
        try {
            val base = File(filesDir, "osmdroid")
            // Eager: internal-storage mkdirs is microseconds; doing it here
            // keeps every later mkdirs() a cheap no-op.
            File(base, "tiles").mkdirs()
            val conf = Configuration.getInstance()
            conf.osmdroidBasePath = base
            conf.osmdroidTileCache = File(base, "tiles")
            conf.userAgentValue = packageName
        } catch (_: Exception) {
            // osmdroid falls back to its own discovery; never break startup.
        }
    }
}
