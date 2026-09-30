# Settings, Privacy, Permissions & Final UI Polish — Phase 9

Persistent, DataStore-backed user preferences; a truthful in-app Privacy
screen; a permissions audit; canonical storage/deletion rules; and a final
UI polish pass over every screen. No new engines, no cloud, no accounts, no
analytics — Phases 1–8 are reused untouched.

## Architecture

```
AppSettings (settings/AppSettings.kt)          pure value object + sanitizer
        │  15 persisted preferences, .sanitized() clamps every field
        ▼
SettingsRepository (data/SettingsRepository.kt)  existing DataStore, extended
        │  new keys + flows + setters, appSettings Flow, resetUserPreferences()
        ▼
SettingsViewModel (ui/phase1/SettingsViewModel.kt)
        │  UI state, async storage stats, clear-cache, reset, 4K encoder probe
        ▼
SettingsScreen / PrivacyScreen / LicensesScreen / HelpScreen / AboutScreen
        │  Phase1Nav routes: settings, privacy, licenses (+ drawer item)
        ▼
MainActivity / Phase1ViewModel / PreviewScreens / VideoPreviewScreen
   theme applied without restart; playback speed, follow-camera default,
   autoplay, map-style/route/marker defaults, resetComposition() all read
   the stored defaults
```

**Key design rules**

- Settings reuse the existing DataStore and theme; no second repository, no
  second storage layer.
- `defaultVideoComposition(settings)` maps stored defaults onto the real
  Phase 6 `VideoComposition` (preset + aspect + resolution + FPS + route /
  marker / start-end / intro-outro fields). Preset-specific fields the
  defaults don't override (overlay position, text size, location overlay)
  survive; shared fields take the user's stored values.
- `StorageOwnership` gates every destructive storage action: cache clearing
  is allowed only under `cache/video_thumbs`, `cache/tiles`, and
  `style_tiles_*`; traversal (`..`) and unknown paths are rejected; a
  history video's output can be deleted only through a known app export URI
  — the user's original Timeline.json and unrelated files are never
  deletable through the app.
- `resetUserPreferences()` removes only the Phase 9 preference keys. Room
  history, generated videos, imports, the current per-video composition, and
  the session import cache are untouched.

## What was built

**Persistent settings (15)** — Appearance (theme System/Light/Dark),
Map (camera mode, route visibility, start/end markers), Video (composition
preset, aspect, resolution, FPS, route/marker/start-end/intro-outro
defaults), Playback (speed, follow-camera default, autoplay), plus the
existing map-style key.

**Screens** — Settings (grouped sections), Privacy (truthful: timeline
parsing, GeoNames lookup, rendering, and history are 100% on-device;
network is used only for map tiles), Licenses (third-party software + data
attribution: osmdroid, CARTO/Esri/OpenTopoMap tiles, GeoNames CC-BY 4.0 —
no project-license claim, none exists), expanded Help, improved About
(version from `BuildConfig.VERSION_NAME`, `buildConfig = true` added).

**Permissions audit** — manifest declares exactly four permissions:
`INTERNET` (map tiles), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING`
(Phase 7 export), `POST_NOTIFICATIONS` (export progress). No location,
camera, microphone, contacts, legacy storage, or `MANAGE_EXTERNAL_STORAGE`.
The main activity is exported; the export service is not.

**Deletion rules (canonical)** — history delete order: video file first,
then thumbnail + Room metadata; if file deletion fails the metadata is
kept and an error is shown. `refreshAvailability()` prunes records whose
video was deleted outside the app on the next library load. The import
flow never deletes files.

**UI polish** — dynamic theme without activity restart; stored playback
speed for newly built timelines; follow-camera default for new engines;
autoplay drives the real `VideoPreviewController`; timeline preview
applies persisted map style / route / marker defaults; storage measurement
off the main thread; 4K probed asynchronously via `EncoderProbe` and
disabled when unsupported; accessibility descriptions added; Help text
corrected to match the real stale-entry pruning behavior; the misleading
mock Customize/Generate/Result branch was removed (real flow:
Timeline Preview → Video Preview → Phase 7 export → result → My Videos).

**Latent bugs fixed while compiling the tests** (Gradle can't run here, so
these would have surfaced in Android Studio): wrong-package imports of
`VideoMarkerStyle` (`AppSettings`, `SettingsRepository`, `SettingsScreens`)
and `RouteDrawMode` (`CompositionFrameRenderer`); a duplicate
`AnimationPoint.toMapPoint()` extension (kept the `map` package's, removed
the `animation` one); dead `AnimSpeed`/`VideoResolution`/`AspectRatio`/
`VideoQuality`/`FrameRate` enums, dead `MockVideo` class, dead
`MockData.videos`/`generationSteps`, and their unused imports.

## Tests

`app/src/test/.../settings/Phase9SettingsTest.kt` — 25 JVM tests, all
green: defaults, playback-speed sanitization, theme/preset fallbacks,
composition mapping, cache ownership, traversal/unknown-path rejection,
known-export URI gate, imported-Timeline rejection, byte formatting,
manifest allow/deny lists, no-logging guard, import-flow delete guard.

Regression: full pure JVM suite — **191/198 pass**. The 7 failures are the
pre-existing `TimelineParserTest` direct-segment-shape cases (unchanged
since Phase 2; unrelated to Phase 9). Phase 8's 22 history tests: 22/22
green. Compiled + run with standalone kotlinc/JUnit; Gradle, lint, APK,
and on-device behavior remain Android Studio work.

## Not verified here

Gradle build, lint, APK, Compose/Room/MediaStore integration, MediaCodec
export, runtime behavior, device QA — the sandbox cannot run Gradle
(loopback TCP corruption) and has no Android SDK. Sara verifies in
Android Studio.
