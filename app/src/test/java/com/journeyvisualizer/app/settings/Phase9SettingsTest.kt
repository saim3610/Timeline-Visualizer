package com.journeyvisualizer.app.settings

import com.journeyvisualizer.app.composition.CompositionCameraMode
import com.journeyvisualizer.app.composition.CompositionPresets
import com.journeyvisualizer.app.composition.VideoAspectRatio
import com.journeyvisualizer.app.composition.VideoFps
import com.journeyvisualizer.app.map.VideoMarkerStyle
import com.journeyvisualizer.app.composition.VideoResolutionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 9 JVM tests: settings defaults/sanitization, composition mapping,
 * storage ownership rules, and project-guard scans (manifest permissions,
 * logging hygiene, imported-Timeline protection).
 *
 * Pure Kotlin — no Android framework needed. The project-guard tests locate
 * the project root by walking up from the working directory.
 */
class Phase9SettingsTest {

    // ------------------------------------------------------------------
    // AppSettings defaults
    // ------------------------------------------------------------------

    @Test fun `defaults - appearance is system theme`() {
        assertEquals(AppTheme.SYSTEM, AppSettings.DEFAULT.theme)
    }

    @Test fun `defaults - map camera follows journey and markers visible`() {
        val d = AppSettings.DEFAULT
        assertEquals(CompositionCameraMode.FOLLOW_JOURNEY, d.mapCameraMode)
        assertTrue(d.showRouteByDefault)
        assertTrue(d.showStartEndByDefault)
    }

    @Test fun `defaults - video defaults are real renderer capabilities`() {
        val d = AppSettings.DEFAULT
        assertEquals(DefaultPreset.TRAVEL, d.defaultPreset)
        assertEquals(VideoAspectRatio.SIXTEEN_NINE, d.defaultAspect)
        assertEquals(VideoResolutionPreset.P1080, d.defaultResolution)
        assertEquals(VideoFps.FPS_30, d.defaultFps)
        assertTrue(d.defaultResolution in AppSettings.RESOLUTIONS)
        assertTrue(d.defaultFps in AppSettings.FRAME_RATES)
    }

    @Test fun `defaults - route marker and intro outro flags`() {
        val d = AppSettings.DEFAULT
        assertTrue(d.defaultRouteVisible)
        assertEquals(VideoMarkerStyle.STANDARD, d.defaultMarkerStyle)
        assertTrue(d.defaultStartEndMarkers)
        assertFalse(d.defaultIntroOutro)
    }

    @Test fun `defaults - playback`() {
        val d = AppSettings.DEFAULT
        assertEquals(1.0, d.defaultPlaybackSpeed, 0.0)
        assertTrue(d.followCameraByDefault)
        assertFalse(d.autoplayPreview)
    }

    // ------------------------------------------------------------------
    // sanitized()
    // ------------------------------------------------------------------

    @Test fun `sanitized keeps every supported playback speed`() {
        for (speed in AppSettings.PLAYBACK_SPEEDS) {
            val s = AppSettings.DEFAULT.copy(defaultPlaybackSpeed = speed).sanitized()
            assertEquals(speed, s.defaultPlaybackSpeed, 0.0)
        }
    }

    @Test fun `sanitized coerces unsupported playback speed to 1x`() {
        for (bad in listOf(0.0, -1.0, 1.5, 3.0, 100.0, Double.NaN)) {
            val s = AppSettings.DEFAULT.copy(defaultPlaybackSpeed = bad).sanitized()
            assertEquals("speed $bad", 1.0, s.defaultPlaybackSpeed, 0.0)
        }
    }

    @Test fun `sanitized does not touch other fields`() {
        val inSettings = AppSettings.DEFAULT.copy(
            defaultPlaybackSpeed = 9.9,
            theme = AppTheme.DARK,
            defaultAspect = VideoAspectRatio.NINE_SIXTEEN,
        )
        val out = inSettings.sanitized()
        assertEquals(AppTheme.DARK, out.theme)
        assertEquals(VideoAspectRatio.NINE_SIXTEEN, out.defaultAspect)
        assertEquals(1.0, out.defaultPlaybackSpeed, 0.0)
    }

    // ------------------------------------------------------------------
    // fromKey
    // ------------------------------------------------------------------

    @Test fun `AppTheme fromKey round-trips and falls back to system`() {
        for (theme in AppTheme.entries) {
            assertEquals(theme, AppTheme.fromKey(theme.key))
        }
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("neon"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(""))
    }

    @Test fun `DefaultPreset fromKey round-trips and falls back to travel`() {
        for (preset in DefaultPreset.entries) {
            assertEquals(preset, DefaultPreset.fromKey(preset.key))
        }
        assertEquals(DefaultPreset.TRAVEL, DefaultPreset.fromKey(null))
        assertEquals(DefaultPreset.TRAVEL, DefaultPreset.fromKey("cinematic"))
    }

    // ------------------------------------------------------------------
    // defaultVideoComposition mapping
    // ------------------------------------------------------------------

    @Test fun `composition mapping applies every default override`() {
        val settings = AppSettings.DEFAULT.copy(
            defaultPreset = DefaultPreset.TRAVEL,
            defaultAspect = VideoAspectRatio.NINE_SIXTEEN,
            defaultResolution = VideoResolutionPreset.P720,
            defaultFps = VideoFps.FPS_60,
            mapCameraMode = CompositionCameraMode.FIXED_OVERVIEW,
            defaultRouteVisible = false,
            defaultMarkerStyle = VideoMarkerStyle.MINIMAL_DOT,
            defaultStartEndMarkers = false,
            defaultIntroOutro = true,
        )
        val c = defaultVideoComposition(settings)
        assertEquals(VideoAspectRatio.NINE_SIXTEEN, c.aspectRatio)
        assertEquals(VideoResolutionPreset.P720, c.resolutionPreset)
        assertEquals(VideoFps.FPS_60, c.fps)
        assertEquals(CompositionCameraMode.FIXED_OVERVIEW, c.cameraMode)
        assertFalse(c.routeVisible)
        assertEquals(VideoMarkerStyle.MINIMAL_DOT, c.markerStyle)
        assertFalse(c.startEndMarkers)
        assertTrue(c.introEnabled)
        assertTrue(c.outroEnabled)
    }

    @Test fun `composition mapping keeps preset identity for each base`() {
        val travel = defaultVideoComposition(AppSettings.DEFAULT.copy(defaultPreset = DefaultPreset.TRAVEL))
        val social = defaultVideoComposition(AppSettings.DEFAULT.copy(defaultPreset = DefaultPreset.SOCIAL))
        val minimal = defaultVideoComposition(AppSettings.DEFAULT.copy(defaultPreset = DefaultPreset.MINIMAL))
        // Preset-specific fields that the default overrides do NOT touch survive.
        assertEquals(CompositionPresets.travel().overlayPosition, travel.overlayPosition)
        assertEquals(CompositionPresets.social().overlayPosition, social.overlayPosition)
        assertEquals(CompositionPresets.social().textSize, social.textSize)
        assertFalse(minimal.showLocation)
        assertTrue(social.showLocation)
        // The user's stored defaults DO override the shared fields on every base.
        assertEquals(VideoMarkerStyle.STANDARD, travel.markerStyle)
        assertEquals(VideoMarkerStyle.STANDARD, social.markerStyle)
        assertEquals(VideoMarkerStyle.STANDARD, minimal.markerStyle)
    }

    @Test fun `composition mapping does not mutate the shared preset`() {
        val before = CompositionPresets.travel()
        defaultVideoComposition(AppSettings.DEFAULT.copy(defaultAspect = VideoAspectRatio.ONE_ONE))
        assertEquals(before, CompositionPresets.travel())
    }

    @Test fun `composition mapping sanitizes corrupt settings first`() {
        val c = defaultVideoComposition(AppSettings.DEFAULT.copy(defaultPlaybackSpeed = 123.0))
        // Mapping must not throw; composition carries the rest of the defaults.
        assertEquals(VideoResolutionPreset.P1080, c.resolutionPreset)
    }

    // ------------------------------------------------------------------
    // StorageOwnership.isClearableCachePath
    // ------------------------------------------------------------------

    @Test fun `cache ownership allows owned dirs and nested children`() {
        assertTrue(StorageOwnership.isClearableCachePath("video_thumbs"))
        assertTrue(StorageOwnership.isClearableCachePath("tiles"))
        assertTrue(StorageOwnership.isClearableCachePath("video_thumbs/abc123.jpg"))
        assertTrue(StorageOwnership.isClearableCachePath("tiles/12/345/678.png"))
        assertTrue(StorageOwnership.isClearableCachePath("style_tiles_render42"))
        assertTrue(StorageOwnership.isClearableCachePath("style_tiles_render42/z/x/y.tile"))
    }

    @Test fun `cache ownership rejects traversal and absolute escapes`() {
        assertFalse(StorageOwnership.isClearableCachePath("../video_thumbs"))
        assertFalse(StorageOwnership.isClearableCachePath(".."))
        assertFalse(StorageOwnership.isClearableCachePath("video_thumbs/../../etc"))
        assertFalse(StorageOwnership.isClearableCachePath("video_thumbs\\..\\secret"))
        assertFalse(StorageOwnership.isClearableCachePath("tiles/../databases"))
    }

    @Test fun `cache ownership rejects unknown locations`() {
        assertFalse(StorageOwnership.isClearableCachePath(""))
        assertFalse(StorageOwnership.isClearableCachePath("   "))
        assertFalse(StorageOwnership.isClearableCachePath("images"))
        assertFalse(StorageOwnership.isClearableCachePath("databases"))
        assertFalse(StorageOwnership.isClearableCachePath("code_cache"))
        assertFalse(StorageOwnership.isClearableCachePath("video_thumbs_backup"))
        assertFalse(StorageOwnership.isClearableCachePath("my_tiles"))
    }

    // ------------------------------------------------------------------
    // StorageOwnership.isDeletableExportUri
    // ------------------------------------------------------------------

    @Test fun `export uri gate only allows known app-created exports`() {
        val known = setOf(
            "content://media/external/video/media/42",
            "file:///storage/emulated/0/Movies/Journey%20Visualizer/jv.mp4",
        )
        assertTrue(StorageOwnership.isDeletableExportUri("content://media/external/video/media/42") { it in known })
        assertTrue(StorageOwnership.isDeletableExportUri("file:///storage/emulated/0/Movies/Journey%20Visualizer/jv.mp4") { it in known })
        // Unknown URIs are never deletable, even with a plausible shape.
        assertFalse(StorageOwnership.isDeletableExportUri("content://media/external/video/media/43") { it in known })
        assertFalse(StorageOwnership.isDeletableExportUri("file:///sdcard/Download/other.mp4") { it in known })
    }

    @Test fun `export uri gate rejects blank and non-uri references`() {
        assertFalse(StorageOwnership.isDeletableExportUri(null) { true })
        assertFalse(StorageOwnership.isDeletableExportUri("") { true })
        assertFalse(StorageOwnership.isDeletableExportUri("   ") { true })
        assertFalse(StorageOwnership.isDeletableExportUri("https://example.com/v.mp4") { true })
        assertFalse(StorageOwnership.isDeletableExportUri("/sdcard/Movies/x.mp4") { true })
        assertFalse(StorageOwnership.isDeletableExportUri("Timeline.json") { true })
    }

    @Test fun `imported Timeline json is never a deletable export`() {
        // Even if a Timeline.json URI were somehow recorded, the gate must
        // refuse it unless the app's own history explicitly knows it.
        val timelineUri = "content://com.android.providers.downloads.documents/document/7"
        assertFalse(StorageOwnership.isDeletableExportUri(timelineUri) { false })
    }

    // ------------------------------------------------------------------
    // StorageOwnership.formatBytes
    // ------------------------------------------------------------------

    @Test fun `formatBytes renders human sizes and never throws`() {
        assertEquals("0 B", StorageOwnership.formatBytes(0))
        assertEquals("512 B", StorageOwnership.formatBytes(512))
        assertEquals("1 KB", StorageOwnership.formatBytes(1024))
        assertEquals("1.5 KB", StorageOwnership.formatBytes(1536))
        assertEquals("1 MB", StorageOwnership.formatBytes(1024 * 1024L))
        assertEquals("2.5 MB", StorageOwnership.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1 GB", StorageOwnership.formatBytes(1024L * 1024 * 1024))
        assertEquals("—", StorageOwnership.formatBytes(-1))
        assertEquals("—", StorageOwnership.formatBytes(Long.MIN_VALUE))
    }

    // ------------------------------------------------------------------
    // Project guards: manifest permissions
    // ------------------------------------------------------------------

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            if (File(dir, "app/src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: error("project root not found from ${System.getProperty("user.dir")}")
        }
    }

    private fun manifestPermissions(): List<String> {
        val manifest = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()
        return Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
    }

    @Test fun `manifest only declares the audited permission set`() {
        val allowed = setOf(
            "android.permission.INTERNET", // map tile downloads only
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING", // Phase 7 export
            "android.permission.POST_NOTIFICATIONS", // export progress
        )
        val declared = manifestPermissions()
        assertTrue("manifest declares no permissions at all", declared.isNotEmpty())
        for (perm in declared) {
            assertTrue("unexpected permission: $perm", perm in allowed)
        }
    }

    @Test fun `manifest never requests privacy-sensitive permissions`() {
        val text = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()
        val denied = listOf(
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION",
            "CAMERA",
            "RECORD_AUDIO",
            "READ_CONTACTS",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
        )
        for (perm in denied) {
            assertFalse("privacy-sensitive permission present: $perm", perm in text)
        }
    }

    // ------------------------------------------------------------------
    // Project guards: logging hygiene
    // ------------------------------------------------------------------

    @Test fun `production sources contain no logging calls`() {
        val srcRoot = File(projectRoot(), "app/src/main/java")
        val hits = mutableListOf<String>()
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                    val code = t.substringBefore("//")
                    if (Regex("""\bLog\.[divwef]\s*\(""").containsMatchIn(code) ||
                        Regex("""\bprintln\s*\(""").containsMatchIn(code)
                    ) {
                        hits.add("${file.name}:${index + 1}: $t")
                    }
                }
            }
        assertTrue("logging calls in production sources:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    // ------------------------------------------------------------------
    // Project guards: imported Timeline protection
    // ------------------------------------------------------------------

    @Test fun `import flow never deletes files`() {
        val candidates = listOf(
            "app/src/main/java/com/journeyvisualizer/app/ui/phase1/Phase1ViewModel.kt",
            "app/src/main/java/com/journeyvisualizer/app/ui/phase1/ImportState.kt",
        )
        for (rel in candidates) {
            val text = File(projectRoot(), rel).readText()
            val inBlockComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val code = inBlockComments.lines()
                .filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") }
                .joinToString("\n")
            assertFalse(
                "$rel must never delete files (imported Timeline.json is read-only)",
                Regex("""\.delete\s*\(""").containsMatchIn(code),
            )
        }
    }
}
