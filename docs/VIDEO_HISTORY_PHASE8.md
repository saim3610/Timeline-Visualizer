# Video History — Phase 8

Local History / My Videos system for Timeline Visualizer. Every successfully
finalized and verified Phase 7 export is automatically registered; the user
can browse, play, inspect, rename, delete, and share videos — all on-device.

## Architecture

```
Phase 7 export (verified MP4)
        │
        ▼
HistoryRegistration.build()      pure builder: render inputs + validated
        │                        output metadata → VideoHistoryItem
        ▼
HistoryRepository ──► Room (journey_history.db, table video_history)
        │              metadata + URI references ONLY — never MP4 binaries
        │
        ├──► MediaStore (Movies/Timeline Visualizer) — the actual MP4s,
        │    untouched by history except delete/rename/share intents
        │
        └──► ThumbnailStore (cache/video_thumbs/<id>.jpg) — small private
             JPEGs; history works fine without them
        │
        ▼
HistoryViewModel ──► MyVideosScreen / VideoDetailScreen
   (Room Flow → sort/search/filter → UI state; rename/delete/share)
```

**Key design rules**

- The MP4 file is the source of truth for availability. The database never
  overrules it: if the file is gone, the record is cleaned up (orphan
  handling), and no UI claims a video exists that cannot be played.
- Stable video IDs (UUIDs) are used for navigation
  (`video_detail/{videoId}`); whole objects are never passed through routes.
- History is registered only after `OutputValidator` verifies the MP4.
  Failed, cancelled, or corrupt renders create no record.
- Registration is best-effort: if Room insertion fails, the already
  finalized playable MP4 is not invalidated. The result screen then shows
  "My Videos" (history list) instead of "Details".
- Rename never breaks the content URI: the MediaStore file rename is
  attempted (app-owned files, API 29+), but the friendly display name always
  updates in the database even if the file rename fails.
- Delete removes the actual file first. If file deletion fails, the metadata
  record is preserved and an error is shown.

## Database (Room)

First and only database in the app: `journey_history.db`, version 1, table
`video_history` (no migration needed — first schema; no destructive fallback).

Columns: stable `id`, `media_store_id`, `content_uri`, `display_name`,
`original_file_name`, `created_at_ms`, `modified_at_ms`, `duration_ms`,
`width`, `height`, `fps`, `size_bytes`, `aspect_ratio`, `map_style`,
`timeline_start_ms`, `timeline_end_ms`, `event_count`, `start_location`,
`end_location`, `status`, `thumbnail_path`.

- `HistoryDatabase` — singleton via `Room.databaseBuilder`; excluded from
  backup (`android:allowBackup="false"` in the manifest); never synced.
- `VideoHistoryDao` — insert (REPLACE), reactive `observeAll`/`observeById`,
  get by id, rename, thumbnail update, delete, count, and a LIKE search.
- `VideoHistoryEntity` ↔ `VideoHistoryItem` mapping in the entity's
  companion/`toItem`.

## Files

New (`app/src/main/java/com/journeyvisualizer/app/history/`):

| File | Role |
|---|---|
| `VideoHistoryItem.kt` | Pure domain model + `routeLabel` / `resolutionLabel` |
| `RenameValidator.kt` | Pure rename rules (empty, 80 chars, traversal, duplicates) + safe `.mp4` name |
| `HistoryListLogic.kt` | Pure sort (newest/oldest/name), local search, 30-day filter |
| `HistoryLabels.kt` | Pure aspect/map-style labels (shared with registration) |
| `VideoHistoryEntity.kt` | Room entity (references only) |
| `VideoHistoryDao.kt` | Room DAO |
| `HistoryDatabase.kt` | Room database singleton |
| `HistoryRepository.kt` | Room + MediaStore + thumbnail orchestration, orphan reconciliation |
| `ThumbnailStore.kt` | `MediaMetadataRetriever` frame (~1 s), ≤480 px JPEGs in app cache |
| `HistoryRegistration.kt` | Post-verification record builder from `Phase7RenderRequest` |

New UI (`ui/phase1/`):

| File | Role |
|---|---|
| `HistoryViewModel.kt` | Loading/Empty/Loaded/Error states; sort, search, recent filter; rename/delete; LRU thumbnail cache; stable-id detail flow |
| `components/VideoPlayer.kt` | Shared platform `VideoView` + `MediaController` (result + detail) |
| `screens/MyVideosScreen.kt` | Search, sort menu, recent chip, lazy cards, actions |
| `screens/VideoDetailScreen.kt` | Player + full metadata + rename/delete/share by stable id |
| `screens/VideoDialogs.kt` | Shared rename / delete-confirm dialogs |

Modified:

- `export/RenderState.kt` — `Completed.historyId: String? = null`.
- `export/ExportService.kt` — after `OutputValidator.validate(...)`,
  `registerInHistory(...)` inserts the record and generates the thumbnail.
- `ui/phase1/screens/VideoExportResultScreen.kt` — shared player, new
  `onOpenDetails`/`onOpenHistory` actions, shared `shareVideo` helper,
  `ActivityNotFoundException` handling.
- `ui/phase1/navigation/Phase1Nav.kt` — `video_detail/{videoId}` route;
  HISTORY now hosts `MyVideosScreen` (old mock `HistoryScreen` untouched).
- `app/build.gradle.kts`, `build.gradle.kts` — Room 2.6.1 via KSP
  (`2.0.21-1.0.25`, matching Kotlin 2.0.21).
- `res/values/strings.xml` — ~30 new strings.

## UI

- **My Videos** (`history` route): search field, sort dropdown
  (Newest/Oldest/Name A–Z), "Recent" (30-day) chip, lazy cards with
  thumbnail-or-placeholder, play overlay, route label
  ("Lahore → Skardu"), date, resolution · duration · size, and a ⋮ menu
  (Play / Share / Rename / Delete). Tapping a card opens the detail screen.
  Empty states: true-empty (with "Upload Timeline" action into the existing
  import flow) vs. no-search-matches (with "Clear search").
- **Video detail** (`video_detail/{videoId}`): large player, title, route,
  created date, duration, resolution, FPS, size, aspect, map style, timeline
  range, event count, and Share / Rename / Delete buttons.
- **Export result** (Phase 7 screen): "Details" opens the new video's detail
  screen when registration returned an id; otherwise "My Videos" opens the
  list. Share uses `ACTION_SEND` + `video/mp4` + content URI + temporary
  read permission, with a graceful message when no app can handle it.

## Metadata provenance

- Start/end names come from the existing Phase 3 GeoNames resolution on the
  animation points (`AnimationPoint.city`) — never invented; null when
  unresolved (the route label is then hidden).
- Timeline range and event count come from the render inputs
  (`AnimationTimeline.points`, `ExportSpec.eventCount`).
- Dimensions/duration/size come from the validated output
  (`OutputValidator.Info`), not from the filename.

## Orphans and external deletion

`HistoryViewModel.refresh()` → `HistoryRepository.refreshAvailability()` on
every screen entry:

- Compares records against `VideoRepository.listVideos()` (our
  `Movies/Timeline Visualizer` directory) plus a direct reachability check.
- Records whose video vanished (deleted in Gallery/Files) are removed with
  their thumbnails. Orphan thumbnails are swept too.
- `sweepPendingLeftovers()` removes `IS_PENDING` leftovers in our directory
  from crashed renders — but **never while a render is active**
  (`ExportProgressBus.isActive()` guard), so an in-flight export's pending
  entry cannot be deleted mid-render. Only our directory is ever scanned.

## Privacy

Everything is local: Room database, MediaStore videos, cache thumbnails.
No accounts, no sync, no analytics, no uploads. The manifest keeps
`allowBackup="false"`.

## Tests

Pure-JVM (standalone `kotlinc`, JUnit 4 — Gradle is unusable in this
sandbox):

- New `history/HistoryLogicTest.kt`: 22 tests — rename validation
  (empty/too-long/traversal/duplicate/valid, safe filename, default display
  name), sorting (newest/oldest/name), local search (name/start/end, empty
  query, null locations), route/resolution labels, duplicate exports staying
  separate, duration/size formatting, aspect/map-style labels. **22/22 green.**
- Full regression of the pure suites (Phases 2–7 + 8): **171/178 green.**
  The 7 failures are all pre-existing `TimelineParserTest` cases about a
  `{"point": ..., "timestamp": ...}` direct-per-segment JSON shape that the
  Phase 2 parser never supported (it reads `timelinePath`/`visit`/`visits`
  segments); they are unrelated to Phase 8 and untouched by it.

Not verifiable here (needs Android Studio / a device): Gradle build, lint,
APK, Room on-device behavior, MediaStore deletion/rename confirmation,
thumbnail rendering, real playback/sharing, reboot persistence, lifecycle.

## Known limitations

- Room DAO/database tests need Robolectric or instrumentation; only
  statically audited here.
- The DAO `search()` is currently unused — search runs in-memory over the
  observed list (fine for a local library; the DB query exists if a large
  history ever needs it).
- Thumbnail generation runs on the export service thread right after
  finalization; a very large backlog of exports could briefly delay the
  service teardown — bounded by a single frame extraction per video.

## Phase 9 recommendation

**Settings, Privacy, Permissions & Final UI Polish** — app settings screen
(theme, units, default map style), permission rationale flows, storage usage
overview, and the final design-system pass over all screens.
