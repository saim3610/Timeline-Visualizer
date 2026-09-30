# Phase 4 — Interactive Timeline Map (2026-09-30)

## What was built

A real interactive map on the Timeline Preview screen (Phase 1 UI), backed by
the **osmdroid** library (`org.osmdroid:osmdroid-android:6.1.20`, added to
`app/build.gradle.kts`). osmdroid was chosen because the existing map code
(`map/TileMath.kt`, `TileCache.kt`, `JourneyEngine.kt`, `FrameRenderer.kt`) is a
custom CARTO raster-tile *video-frame* pipeline — it has no interactive
`MapView`. osmdroid supplies gestures, markers, polylines, custom tile sources
and lifecycle APIs while letting us keep the project's CARTO tile architecture.

## New files

- `map/BasemapStyle.kt` — STANDARD / SATELLITE / HYBRID / TERRAIN tile specs +
  attribution. Standard reuses the project's CARTO Voyager tiles; Satellite
  uses Esri World Imagery (`z/y/x` order); Hybrid overlays Esri Boundaries &
  Places labels; Terrain uses OpenTopoMap (capped at zoom 17).
- `map/MapPoint.kt` — stable timeline index + raw lat/lng/timestampMs +
  optional `ResolvedLocation`; Web-Mercator mappability check;
  `MapCameraState`.
- `map/MapDataMapper.kt` — pure logic: `ResolvedPoint → MapPoint`, mappable
  filtering, bounds, and **display-only** chronological decimation (records
  are never altered/deleted).
- `map/InteractiveMapController.kt` — UI-agnostic interface Phase 5's
  animation engine programs against (setMapStyle, setRoute, clearRoute,
  setSelectedPoint, moveCameraToPoint, fitTimelineBounds, camera state,
  marker-tap listener, attach/detach lifecycle).
- `map/OsmMapController.kt` — osmdroid implementation: multi-touch +
  rotation gestures, chronological green polyline, start/end markers,
  zoom-dependent grid clustering (≤120 markers/zoom), route display
  decimation (≤4,000 pts), green selection ring, camera animation, fit-bounds
  with padding, offline-safe (no crash on tile failure).
- `map/TileImageFetcher.kt` — fetches real provider tiles over HTTP into a
  disk cache (never the GeoNames/location data) to render genuine map-style
  thumbnails.
- `ui/phase1/map/TimelineMap.kt` — Compose `AndroidView` wrapper: lifecycle
  forwarding (resume/pause/detach), fit + style-change controls,
  always-visible attribution, offline banner.
- `ui/phase1/map/MapStyleThumbnail.kt` — style cards show real tile imagery
  (neutral placeholder while loading / on failure).

## Changed files

- `ui/phase1/screens/PreviewScreens.kt` — Timeline Preview now hosts the real
  map when imported data exists; marker taps ↔ timeline list selection stay
  in sync via the stable `pointIndex`; invalid-coordinate count is surfaced;
  empty-map state for no data.
- `ui/phase1/navigation/Phase1Nav.kt` — Timeline Preview gets `onMapStyle`.
- `ui/phase1/Phase1ViewModel.kt` — `selectedMapIndex` + style load/save
  helpers.
- `data/SettingsRepository.kt` — persisted `MAP_STYLE`.
- `ui/phase1/PreviewMapper.kt` — `MapPin` carries `pointIndex`.
- `ui/phase1/components/MapPreview.kt` — `MapPreview` keeps its decorative
  role for other screens; style cards use real thumbnails.
- `ui/phase1/components/LocationDetail.kt` — optional event date/time line.
- `ui/phase1/screens/StyleScreens.kt` — applied style is persisted.
- `res/values/strings.xml` — map strings (summary, fit, style, offline,
  empty state).

## Rules honored

- Raw Timeline coordinates/timestamps are preserved end-to-end; GeoNames data
  is display metadata only (never replaces a coordinate).
- Timeline parsing + GeoNames resolution stay local; no timeline JSON,
  coordinates, or GeoNames queries are uploaded. Only raster map tiles are
  fetched over the network.
- No new region/country-code/GeoName-ID/activity/duration fields invented.
- Route polyline is ordered by `timestampMs`; selection uses the stable
  timeline index, never city names.
- No Phase 5 work (animation, video/MP4, export).

## Tests

`app/src/test/.../map/MapDataMapperTest.kt` (18) and `BasemapStyleTest.kt`
(7): raw-coordinate preservation, mappability filtering, bounds, stable
indexes, unresolved metadata passthrough, timestamp ordering, decimation
caps, tile URL construction incl. Esri `z/y/x`, style registration, overlays,
attribution. **25/25 pass** — compiled with kotlinc and run under JUnit 4 in
this sandbox (Gradle itself remains impossible here; see below).

## Build status

- Full Gradle/Android build: NOT run (sandbox limitation — Gradle daemon
  protocol is corrupted here). The user builds in Android Studio.
- New pure-JVM map logic (mapper, point, styles) + 25 tests: **compiled and
  green** via standalone kotlinc.
- osmdroid/Compose/Android integration code: statically audited only
  (API signatures verified against osmdroid 6.1.20 sources/javadoc; icon and
  resource references checked against the project). Not runtime-verified.

## Known limitations

- `Journey` exposes one chronological point list with no disconnected-segment
  boundaries, so the route renders as one continuous line (no invented
  segmentation).
- Points outside Web-Mercator range are excluded from the map and counted in
  the UI warning.
- Offline banner reflects connectivity state; per-tile download failures are
  tolerated silently by osmdroid's tile pipeline (no crash), not surfaced as
  errors.
- Map style thumbnails need network once; they are disk-cached afterwards.
- Provider terms: tile endpoints were verified reachable (HTTP 200); legal
  compatibility of each provider's terms with this app's distribution still
  needs the owner's explicit review before release.
