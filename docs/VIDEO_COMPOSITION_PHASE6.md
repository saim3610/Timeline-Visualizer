# Phase 6 — Professional Video Preview & Composition

Date: 2026-09-30. Status: implemented; pure-Kotlin layer verified 31/31
green on the JVM. Android/Compose/osmdroid integration is statically audited
only — the sandbox cannot run Gradle (see AGENTS.md); Sara builds and runs
the app in Android Studio.

## What this phase is

A reusable composition state plus an interactive, true-to-export video
preview built **on top of the real Phase 5 animation engine** — not a second
animation system. The same timeline, animation settings, camera logic, route
logic, marker positions, map styles, and GeoNames metadata feed both the
preview and the future Phase 7 exporter.

Out of scope (per authorization): MP4 encoding, H.264/H.265, audio, FFmpeg,
background rendering, gallery export, sharing, compression, cloud, watermark
systems, accounts, payments, ads, analytics.

## Architecture

```
VideoComposition            (pure data: what the video looks like)
  │  VideoPreviewController (video clock: intro + journey + outro)
  │    └── AnimationEngine  (Phase 5: the one and only journey animation)
  │           └── AnimationTimeline (deterministic compression, real timestamps)
  └── ExportSpec            (Phase 7 handoff: counts + settings, never JSON)
```

### `composition/` package (pure Kotlin, JVM-testable)

- `VideoComposition.kt` — the whole video recipe: aspect ratio (16:9, 9:16,
  1:1), resolution preset (720p/1080p/1440p/4K), FPS (24/30/60), duration
  mode (Auto/Short/Medium/Long/Custom 10–600 s), map style, camera mode,
  route (draw mode, visibility, width, opacity), marker style, start/end
  markers, overlays (location, date/time, progress), user-controlled
  title/subtitle (never auto-invented), typography (position, size, weight,
  align), intro/outro cards (1–5 s each, optional). Plus `CompositionCodec`
  (`k=v;k=v`, URL-escaped, never throws; unknown/missing → defaults).
- `CompositionPresets.kt` — Travel (16:9 journey video), Social (9:16,
  large centered text), Minimal (no text overlays, thin marker).
- `CompositionValidator.kt` — timeline present, animation non-empty, custom
  duration in range, title/subtitle length, intro/outro ranges. Issues are
  reported, never silently corrected.
- `ExportSpec.kt` — `VideoComposition.toExportSpec(timeline, timelineRef)`
  builds the Phase 7 configuration: export WxH, FPS, intro/journey/outro ms,
  total ms, event count, and a **timeline reference (file name), not a JSON
  copy**. Same inputs → same spec (deterministic).
- `VideoPreviewController.kt` — the video clock. Drives the real
  `AnimationEngine` for the journey portion:
  - scrubbing maps deterministically: `videoTime → engine.seekTo(videoTime − intro)`;
  - during playback the engine is the clock: video position snaps to
    `intro + engine.position` each tick;
  - intro/outro are simple title cards advanced by the controller;
  - `bind(newEngine)` re-parks on import/duration rebuild; `detach()` never leaks.

### Map surface (osmdroid, Phase 4/5 code reused)

- `InteractiveMapController` gained default no-op Phase 6 methods; `OsmMapController`
  implements them: follow tuning (dead-zone fraction + throttle; follow =
  tight, smart follow = wide + calm), animated marker styles (Standard,
  Minimal dot, Highlighted ring, Hidden), route appearance (visibility,
  width 0.5–2×, opacity 0.2–1, applied on top of the existing
  dim-full + dark-green-traveled progressive route), start/end marker
  visibility (real first/last timeline coordinates). All null-safe with the
  existing pending-state pattern for attach/detach windows.
- Camera modes reuse the Phase 5 camera: Follow Journey / Smart Follow set
  engine `FOLLOW` with different tuning; Fixed Overview sets engine `FREE`
  and fits the route bounds once. User touch still suspends follow (FREE);
  Recenter resumes it.

### ViewModel (`Phase1ViewModel`)

- `videoComposition` (Compose state, survives rotation) + `videoPreviewController`
  wrapping the shared `AnimationEngine` (one journey animation only).
- Changing **duration-related** fields rebuilds `AnimationTimeline`/`AnimationEngine`
  from the kept in-memory resolved points — **never reparses JSON, never
  re-runs GeoNames**; events are never dropped, original timestamps stay
  correct. All other composition changes only touch the controller/map.
- `applyCompositionPreset`, `resetComposition` (defaults only — timeline,
  GeoNames results, and the imported file are untouched), DataStore
  persistence via `SettingsRepository` (`CompositionCodec` string).
- `onCleared` detaches controller + engine.

### UI (`ui/phase1/screens/VideoPreviewScreen.kt`)

Entry: "Video Preview" button on Timeline Preview (enabled when an animation
engine exists) → `Phase1Routes.VIDEO_PREVIEW`.

- Aspect-ratio frame (16:9 / 9:16 / 1:1) with the **live osmdroid map** —
  never stretched; geography keeps true proportions.
- Real overlays from GeoNames data: location name (or "Location unavailable"
  with raw coordinates), date/time, title/subtitle, progress bar. 9:16 uses
  larger safe margins (clear of OS notification + social UI zones).
- Intro/outro title cards (user text; default intro falls back to "My Journey").
- Transport: play/pause + video-clock scrubber with time labels.
- Fullscreen dialog: the single map controller moves to the dialog (only one
  `TimelineMap` is ever composed, so the two MapViews never fight).
- Settings sections: presets, aspect, resolution (export size shown; preview
  itself renders at device resolution — no heavy 4K buffers), FPS, duration
  (with intro/journey/outro breakdown), map style (Standard, Satellite,
  Hybrid, Terrain only), camera, route, marker, overlay, typography,
  intro/outro. Validation issues block "Continue"; the Continue dialog shows
  the validated Phase 7 export spec (informational — no encoding happens).

## Determinism

Same timeline + animation settings + composition ⇒ same preview state and
same `ExportSpec`. Compression reuses Phase 5 `AnimationTimeline.build`;
codec round-trips exactly; controller seeks are pure functions of video time.

## Tests (sandbox, standalone kotlinc — Gradle impossible here)

`app/src/test/.../composition/VideoCompositionTest.kt` — 31/31 green:
aspect proportions; export sizes for every preset × aspect (incl. even
dimensions); 24/30/60 FPS; duration mapping (auto/short/medium/long, custom
total, intro/outro subtraction, 10 s floor); codec round-trip with
special/unicode text; garbage decode → defaults; presets valid + key traits;
reset semantics; validation (no timeline, empty animation, bad custom
duration, overlong title/subtitle, healthy case); export-spec determinism and
timeline-reference behavior; controller total/seek regions/intro→engine
handoff/pause freeze/natural completion/reset.

Regression: Phase 5 (37) + Phase 4 (25) pure tests still 62/62 green.

## Honest build status

- Pure Kotlin (composition, controller, codec, validator, presets, spec):
  compiled and tested green in the sandbox.
- Android/Compose/osmdroid integration (screen, driver, map methods, VM,
  DataStore, navigation, resources): carefully audited by hand; several real
  bugs were found and fixed (lambda shadowing, missing import, fullscreen
  double-map, `toMapPoint` for animation points). **Not compiled** — Gradle
  cannot run in this sandbox (daemon loopback corruption, verified; see
  AGENTS.md). Sara must build in Android Studio and watch for anything the
  audit missed.

## Known limitations

- Playback position is not persisted across process death (Phase 5 limitation).
- Single-point timelines have zero animation duration (validation flags it).
- The preview map needs network tiles; offline shows the cached/empty state
  banner from Phase 4.
- Duration rebuild detaches and recreates the engine; the Timeline Preview
  tab's Phase 5 driver keeps working because it reads `ux.animationEngine`
  fresh on recomposition.
- Composition persists; the imported timeline itself does not survive process
  death (pre-existing Phase 5 limitation) — after a kill, re-import.

## Recommendation: Phase 7 — Deterministic Video Rendering & MP4 Export

Consume `ExportSpec` with a frame-accurate renderer: for each output frame at
the spec's FPS, seek the Phase 5 timeline deterministically, render the
osmdroid map offscreen at the spec's WxH (no stretching — the composition's
aspect ratio already matches), draw overlays/intro/outro with the same
layout code as the preview, and encode H.264 via MediaCodec/MediaMuxer
(existing `export/VideoExporter.kt` is the pre-Phase-6 starting point; it must
be rewired to the composition model, not used as-is). No audio in v1.
