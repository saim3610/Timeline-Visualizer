package com.journeyvisualizer.app.history

/** Sort orders offered by the My Videos screen. */
enum class HistorySort {
    NEWEST_FIRST,
    OLDEST_FIRST,
    NAME_AZ,
}

/**
 * Pure list logic for the history screen: sorting and local search.
 * Search matches display name, start location, and end location only —
 * everything stays on the device.
 */
object HistoryListLogic {

    fun sorted(items: List<VideoHistoryItem>, sort: HistorySort): List<VideoHistoryItem> =
        when (sort) {
            HistorySort.NEWEST_FIRST -> items.sortedByDescending { it.createdAtMs }
            HistorySort.OLDEST_FIRST -> items.sortedBy { it.createdAtMs }
            HistorySort.NAME_AZ ->
                items.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
                )
        }

    fun filtered(items: List<VideoHistoryItem>, query: String): List<VideoHistoryItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { item ->
            item.displayName.lowercase().contains(q) ||
                item.startLocation?.lowercase()?.contains(q) == true ||
                item.endLocation?.lowercase()?.contains(q) == true
        }
    }

    /** "Recent" filter: videos created within the last [days] days. */
    fun recent(items: List<VideoHistoryItem>, days: Int, nowMs: Long): List<VideoHistoryItem> {
        val cutoff = nowMs - days * 24L * 60 * 60 * 1000
        return items.filter { it.createdAtMs >= cutoff }
    }
}
