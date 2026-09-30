package com.journeyvisualizer.app.history

import com.journeyvisualizer.app.ui.util.formatDurationMs
import com.journeyvisualizer.app.ui.util.formatFileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 pure-JVM tests: rename validation, sorting, local search,
 * model labels, duplicate separation, and metadata representation.
 *
 * Android/Room pieces (DAO, repository MediaStore ops, thumbnails) are
 * statically audited only — the sandbox has no Android runtime.
 */
class HistoryLogicTest {

    private fun item(
        id: String,
        name: String,
        createdAtMs: Long,
        start: String? = null,
        end: String? = null,
        durationMs: Long = 42_000,
        sizeBytes: Long = 18_000_000,
        height: Int = 1080,
    ) = VideoHistoryItem(
        id = id,
        mediaStoreId = 1000L + id.hashCode(),
        contentUri = "content://media/external/video/media/${1000 + id.hashCode()}",
        displayName = name,
        originalFileName = "Timeline_2026-09-30_1430.mp4",
        createdAtMs = createdAtMs,
        modifiedAtMs = createdAtMs,
        durationMs = durationMs,
        width = 1920,
        height = height,
        fps = 30,
        sizeBytes = sizeBytes,
        aspectRatio = "16:9",
        mapStyle = "Standard",
        timelineStartMs = 1_772_000_000_000L,
        timelineEndMs = 1_772_500_000_000L,
        eventCount = 137,
        startLocation = start,
        endLocation = end,
        thumbnailPath = null,
    )

    // ------------------------------------------------------------------
    // Rename validation
    // ------------------------------------------------------------------

    @Test fun rename_empty_isInvalid() {
        val r = RenameValidator.validate("   ", emptyList())
        assertTrue(r is RenameValidator.Result.Invalid)
    }

    @Test fun rename_tooLong_isInvalid() {
        val r = RenameValidator.validate("x".repeat(81), emptyList())
        assertTrue(r is RenameValidator.Result.Invalid)
    }

    @Test fun rename_pathTraversal_isInvalid() {
        assertTrue(RenameValidator.validate("../evil", emptyList()) is RenameValidator.Result.Invalid)
        assertTrue(RenameValidator.validate("a/b", emptyList()) is RenameValidator.Result.Invalid)
    }

    @Test fun rename_duplicate_caseInsensitive_isDuplicate() {
        val r = RenameValidator.validate("my trip", listOf("My Trip", "Other"))
        assertTrue(r is RenameValidator.Result.Duplicate)
    }

    @Test fun rename_sameNameOnOtherVideo_only() {
        // The video's own current name is excluded by the caller; a name
        // matching only itself is fine.
        val r = RenameValidator.validate("My Trip", emptyList())
        assertTrue(r is RenameValidator.Result.Ok)
    }

    @Test fun rename_valid_isOk() {
        val r = RenameValidator.validate("My Northern Pakistan Trip", listOf("Other"))
        assertTrue(r is RenameValidator.Result.Ok)
    }

    @Test fun rename_toSafeFileName_stripsUnsafe() {
        assertEquals("My Trip.mp4", RenameValidator.toSafeFileName("My Trip"))
        assertEquals(
            "ab.mp4",
            RenameValidator.toSafeFileName("a/b\\:*?\"<>|"),
        )
        assertEquals("x.mp4", RenameValidator.toSafeFileName("x.mp4"))
        assertEquals("Video.mp4", RenameValidator.toSafeFileName("   "))
    }

    @Test fun rename_defaultDisplayName_dropsExtension() {
        assertEquals(
            "Timeline_2026-09-30_1430",
            RenameValidator.defaultDisplayName("Timeline_2026-09-30_1430.mp4"),
        )
    }

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    @Test fun sort_newestFirst_isDefault() {
        val items = listOf(
            item("a", "Old", 1000),
            item("b", "New", 3000),
            item("c", "Mid", 2000),
        )
        val sorted = HistoryListLogic.sorted(items, HistorySort.NEWEST_FIRST)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test fun sort_oldestFirst() {
        val items = listOf(
            item("a", "Old", 1000),
            item("b", "New", 3000),
        )
        val sorted = HistoryListLogic.sorted(items, HistorySort.OLDEST_FIRST)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test fun sort_name_caseInsensitive() {
        val items = listOf(
            item("a", "zulu", 1000),
            item("b", "Alpha", 2000),
            item("c", "mike", 3000),
        )
        val sorted = HistoryListLogic.sorted(items, HistorySort.NAME_AZ)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    // ------------------------------------------------------------------
    // Search (local)
    // ------------------------------------------------------------------

    @Test fun search_matchesName() {
        val items = listOf(
            item("a", "Northern Trip", 1000),
            item("b", "City Walk", 2000),
        )
        assertEquals(listOf("a"), HistoryListLogic.filtered(items, "northern").map { it.id })
    }

    @Test fun search_matchesStartAndEndLocation() {
        val items = listOf(
            item("a", "Trip", 1000, start = "Lahore", end = "Skardu"),
            item("b", "Walk", 2000, start = "Karachi", end = "Hyderabad"),
        )
        assertEquals(listOf("a"), HistoryListLogic.filtered(items, "skardu").map { it.id })
        assertEquals(listOf("a"), HistoryListLogic.filtered(items, "LAHORE").map { it.id })
    }

    @Test fun search_emptyQuery_returnsAll() {
        val items = listOf(item("a", "A", 1000), item("b", "B", 2000))
        assertEquals(2, HistoryListLogic.filtered(items, "  ").size)
    }

    @Test fun search_noLocationFields_doesNotCrash() {
        val items = listOf(item("a", "Trip", 1000, start = null, end = null))
        assertTrue(HistoryListLogic.filtered(items, "lahore").isEmpty())
        assertEquals(1, HistoryListLogic.filtered(items, "trip").size)
    }

    // ------------------------------------------------------------------
    // Model labels
    // ------------------------------------------------------------------

    @Test fun routeLabel_bothEnds() {
        val i = item("a", "T", 1000, start = "Lahore", end = "Skardu")
        assertEquals("Lahore → Skardu", i.routeLabel)
    }

    @Test fun routeLabel_missingEnd_isNull() {
        assertNull(item("a", "T", 1000, start = "Lahore", end = null).routeLabel)
        assertNull(item("b", "T", 1000, start = null, end = null).routeLabel)
    }

    @Test fun resolutionLabel_fromHeight() {
        assertEquals("1080p", item("a", "T", 1000, height = 1080).resolutionLabel)
        assertEquals("720p", item("b", "T", 1000, height = 720).resolutionLabel)
    }

    // ------------------------------------------------------------------
    // Duplicates stay separate
    // ------------------------------------------------------------------

    @Test fun duplicateExports_remainSeparate() {
        val one = item("id-1", "My Journey", 1000)
        val two = item("id-2", "My Journey (2)", 2000)
        val sorted = HistoryListLogic.sorted(listOf(one, two), HistorySort.NEWEST_FIRST)
        assertEquals(2, sorted.size)
        assertEquals("id-2", sorted[0].id)
        assertEquals("id-1", sorted[1].id)
    }

    // ------------------------------------------------------------------
    // Metadata representation (same values the cards show)
    // ------------------------------------------------------------------

    @Test fun metadata_durationSize_formatting() {
        val i = item("a", "T", 1000, durationMs = 42_000, sizeBytes = 18_874_368)
        assertEquals("0:42", formatDurationMs(i.durationMs))
        assertEquals("18.0 MB", formatFileSize(i.sizeBytes))
    }

    @Test fun metadata_longDuration_hours() {
        assertEquals("1:02:03", formatDurationMs(3_723_000))
    }

    // ------------------------------------------------------------------
    // Registration helpers
    // ------------------------------------------------------------------

    @Test fun aspectLabel_canonical() {
        assertEquals("16:9", HistoryLabels.aspectLabel(1920, 1080))
        assertEquals("9:16", HistoryLabels.aspectLabel(1080, 1920))
        assertEquals("1:1", HistoryLabels.aspectLabel(1080, 1080))
        assertEquals(
            "Standard",
            HistoryLabels.mapStyleLabel(com.journeyvisualizer.app.map.BasemapStyle.STANDARD),
        )
    }
}
