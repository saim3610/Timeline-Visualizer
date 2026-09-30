package com.journeyvisualizer.app.settings

/**
 * Phase 9: storage ownership rules — the SINGLE SOURCE OF TRUTH for what the
 * app may delete automatically.
 *
 * Summary of the canonical lifecycle:
 * 1. Timeline.json selected by the user → read only, NEVER deleted by the app.
 * 2. Video rendering → temporary render files the app owns → cleaned after
 *    success / cancellation / failure / safe restart recovery.
 * 3. Successful MP4 finalization → registered in History (Room metadata +
 *    MediaStore content URI; the MP4 stays in Android-managed storage).
 * 4. User deletes a history item → confirmation → delete the actual owned
 *    video file first → on success delete thumbnail/cache + Room metadata;
 *    on failure keep the metadata and show an actionable error.
 * 5. Cache (thumbnails, map tile caches) may be regenerated → safe to clear.
 *
 * The app must NEVER delete: the user's original Timeline.json, arbitrary
 * videos/images/documents, or anything outside its managed scope.
 *
 * Pure Kotlin so the rules are unit-tested on the JVM.
 */
object StorageOwnership {

    /**
     * Cache directory names (relative to the app cache dir) that the app
     * owns and may clear. Thumbnails and map tiles are regenerable, so
     * clearing them is always safe.
     */
    val OWNED_CACHE_DIRS: Set<String> = setOf(
        "video_thumbs", // Phase 8 history thumbnails (regenerable from the MP4)
        "tiles", // Phase 4 interactive-map tile thumbnails (re-downloadable)
    )

    /**
     * Cache directory name *prefixes* owned by the Phase 7 export renderer
     * for temporary style tiles. The suffix carries a cache tag, so the
     * match is by prefix.
     */
    const val RENDER_TILE_CACHE_PREFIX = "style_tiles_"

    /**
     * True when [relativePath] (a path relative to the app cache dir) is
     * inside one of the app-owned cache locations and is therefore safe for
     * "Clear cache" to remove. Anything else — including absolute paths,
     * parent traversals, and unknown names — returns false.
     */
    fun isClearableCachePath(relativePath: String): Boolean {
        val normalized = relativePath.trim().replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return false
        if (normalized == ".." || normalized.startsWith("../") || normalized.contains("/../")) {
            return false
        }
        val top = normalized.substringBefore('/')
        if (top.isEmpty()) return false
        return top in OWNED_CACHE_DIRS || top.startsWith(RENDER_TILE_CACHE_PREFIX)
    }

    /**
     * True when a history record's stored URI identifies a video the app
     * itself created through the Phase 7 exporter (and registered in
     * Phase 8 history). Only such URIs may be passed to the storage delete
     * path. Foreign content URIs, file paths outside the app's output
     * scope, and null/blank references are never deletable by the app.
     *
     * [isKnownExportUri] must answer from the app's own records (e.g. the
     * Room history table), never from scanning the device.
     */
    fun isDeletableExportUri(uri: String?, isKnownExportUri: (String) -> Boolean): Boolean {
        if (uri.isNullOrBlank()) return false
        val u = uri.trim()
        if (u.startsWith("content://") || u.startsWith("file://")) {
            return isKnownExportUri(u)
        }
        return false
    }

    /**
     * Formats a byte count for the Storage settings screen. Never throws.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${oneDecimal(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${oneDecimal(mb)} MB"
        val gb = mb / 1024.0
        return "${oneDecimal(gb)} GB"
    }

    private fun oneDecimal(v: Double): String {
        val rounded = kotlin.math.round(v * 10) / 10.0
        return if (rounded == kotlin.math.floor(rounded)) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}
