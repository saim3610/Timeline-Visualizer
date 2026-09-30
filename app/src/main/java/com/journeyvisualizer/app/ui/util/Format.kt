package com.journeyvisualizer.app.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(meters: Double, unit: String): String =
    if (unit == "mi") {
        val mi = meters / 1609.344
        if (mi >= 100) "${mi.roundToInt()} mi" else "%.1f mi".format(Locale.US, mi)
    } else {
        val km = meters / 1000.0
        if (km >= 100) "${km.roundToInt()} km" else "%.1f km".format(Locale.US, km)
    }

fun formatDurationMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))

fun formatDateTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US))

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(Locale.US, kb)
    val mb = kb / 1024.0
    return if (mb < 1024) "%.1f MB".format(Locale.US, mb)
    else "%.2f GB".format(Locale.US, mb / 1024.0)
}

fun formatVideoTime(sec: Float): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
