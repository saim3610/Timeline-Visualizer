# Timeline Visualizer (0.3.0)

Turn your Google Timeline export (`Timeline.json`) into an animated travel video —
entirely on your Android device. Import → animated map preview → MP4 export →
video library. No accounts, no cloud, no analytics.

**Status:** Phase 10 — final QA and release preparation. The code is written and
pure-logic unit tests pass; **it has not yet been compiled in Android Studio**,
so open the project there, build, and run it on a real device before release.

## What it does

- **Import** — pick your `Timeline.json` through the system document picker
  (no storage permission needed). The tolerant parser handles current
  direct-array exports, older `{ "semanticSegments": [...] }` exports, visits,
  activities, and `latLng` / E7 / `geo:` / decimal coordinate spellings, with
  GPS-outlier filtering and a report of ignored points.
- **Place names** — offline reverse-geocoding against 34,152 bundled GeoNames
  cities (`assets/geonames/cities.tsv`, CC-BY 4.0). No network, no API key.
- **Preview** — interactive animated map (osmdroid): play/pause/scrub, speed
  control, camera-follow modes, 4 basemap styles (Standard, Satellite, Hybrid,
  Terrain). The preview and the export share one deterministic animation
  engine, so what you see is what the video renders.
- **Video settings** — aspect ratio (16:9, 9:16, 1:1), resolution up to 4K,
  24/30/60 fps, duration presets, route/marker/overlay styling, intro/outro
  cards, and presets (Travel, Social, Minimal).
- **Export** — real MP4 via hardware H.264 (`MediaCodec`) + `MediaMuxer`,
  rendered in a foreground service (`mediaProcessing`) so it keeps going with
  the screen off. Real progress and cancel in the notification. Videos land in
  `Movies/Timeline Visualizer` via MediaStore.
- **My Videos** — on-device library (Room + MediaStore): thumbnails, search,
  sort, rename, play, share, safe delete.
- **Settings & Privacy** — theme (system/light/dark), map/video/playback
  defaults, cache management, license attributions. Everything is explained in
  the in-app Privacy screen.

## Privacy

- Your Timeline file, coordinates, and videos never leave the device.
- No sign-in, no location permission, no analytics, no ads, no crash reporting.
- The only network use is downloading map tiles (CARTO / Esri / OpenTopoMap);
  attribution is shown in the app as each provider requires.

## Permissions (4 — all required)

| Permission | Why |
|---|---|
| `INTERNET` | Map tile downloads |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Background MP4 export |
| `POST_NOTIFICATIONS` | Export progress / cancel notification |

## Build

Requirements: **JDK 17**, **Android Studio** (recent), Android SDK **Platform 35**
+ **Build Tools 35**.

```bash
./gradlew assembleDebug
./gradlew installDebug        # onto a connected device / emulator
```

For a release build, add a `signingConfigs` block (keystore path via
`local.properties` — never commit the keystore) and enable R8/minification in
`app/build.gradle.kts`; then `./gradlew assembleRelease`. See
`docs/RELEASE_CHECKLIST.md` (Phase 10) for the full pre-release steps.

## Get your Timeline.json

On Android: **Settings → Location → Location services → Timeline →
Export Timeline data** → save `Timeline.json` (e.g. Downloads), then import it
in the app. On iPhone: Google Maps → profile → Settings → Personal content →
Export Timeline data, then move the file to the Android device.

## Architecture

```
app/src/main/java/com/journeyvisualizer/app/
├── MainActivity.kt                 # single activity, hosts AppNav
├── data/
│   ├── TimelineParser.kt           # tolerant Timeline.json parser
│   ├── SettingsRepository.kt       # DataStore settings (incl. Phase 9 keys)
│   └── VideoRepository.kt          # MediaStore video library (Movies/…)
├── data/geo/                       # offline GeoNames engine (grid index,
│                                   # nearest-city resolution, LRU cache)
├── map/                            # osmdroid interactive map, basemap styles,
│                                   # tile caches, map data mapping
├── animation/                      # deterministic animation engine
│                                   # (AnimationTimeline.stateAt(t) — pure)
├── composition/                    # video composition model, presets,
│                                   # validation, export spec
├── export/                         # RenderClock → frame renderer → EGL →
│                                   # MediaCodec → MediaMuxer; ExportService
│                                   # (foreground) + progress bus
├── history/                        # Room database (video_history v1),
│                                   # HistoryRepository, thumbnails
├── settings/                       # AppSettings, storage-ownership guard
└── ui/phase1/                      # Compose screens: import flow, animated
                                    # map preview, video preview & settings,
                                    # export dialogs, My Videos, Settings,
                                    # Help, About, Privacy, Licenses
```

Key design decision: **one deterministic animation state** drives both the
Compose preview and the MP4 exporter — each frame is a pure function of its
timestamp, so preview and export can never drift apart.

Per-phase notes live in `docs/` (`GEONAMES_PHASE3.md`, `MAP_PHASE4.md`,
`ANIMATION_PHASE5.md`, `VIDEO_COMPOSITION_PHASE6.md`,
`VIDEO_EXPORT_PHASE7.md`, `VIDEO_HISTORY_PHASE8.md`, `SETTINGS_PHASE9.md`).

## Attribution

- Basemap © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
  © [CARTO](https://carto.com/), Esri, OpenTopoMap — shown in-app per provider.
- Place data: GeoNames (CC-BY 4.0), bundled offline.
- Libraries: osmdroid, Jetpack Compose, Room, DataStore — see the in-app
  Licenses screen.
