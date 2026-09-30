package com.journeyvisualizer.app.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export filename builder (Phase 7). Pure JVM — no Android dependency.
 *
 * Produces `Timeline_2026-09-30_1430.mp4`-style names and guarantees
 * uniqueness against whatever already exists via the [exists] predicate
 * (wired to MediaStore lookups on Android).
 */
object ExportFileNamer {

    private const val STEM = "Timeline"

    fun baseName(nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(nowMs))
        return "${STEM}_${stamp}.mp4"
    }

    /**
     * Returns [base] when it is free, otherwise `Timeline_<stamp> (2).mp4`,
     * `… (3).mp4`, and so on until a free name is found.
     */
    fun uniqueName(base: String, exists: (String) -> Boolean): String {
        if (!exists(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }
}
