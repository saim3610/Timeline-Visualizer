package com.journeyvisualizer.app

import android.app.Application

/**
 * Installs the crash reporter before anything else runs, so even an
 * early startup crash leaves a report the next launch can show.
 */
class TimelineVisualizerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
