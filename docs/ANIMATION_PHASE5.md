# Phase 5 — Timeline Visualization & Animation Engine

Implemented 2026-09-30. Interactive journey playback on the Phase 4 map:
deterministic time compression, great-circle interpolation, progressive
route drawing, moving marker, follow/free camera, and full transport
controls. No video encoding — that is Phase 6.

## Architecture

```
ResolvedPoint (Phase 3)
      │  Phase1ViewModel.runImport, after GeoNames resolution
      ▼
AnimationTimeline.build(...)          pure, deterministic, built once
      │  AnimationPoint list + AnimationSegment list
      ▼
AnimationEngine (viewModelScope)      single playback state machine
      │  StateFlow: state / frame / speed / followMode (~30 FPS ticker)
      ▼
TimelinePreviewScreen
 ├── AnimationDriver  (frame → controller calls)
 ├── TimelineMap      (Phase 4 map, controller hoisted to caller)
 └── AnimationPanel   (playback UI; reads engine flows only)
      ▼
OsmMapController (+ InteractiveMapController animation surface)
```

One animation system only: `AnimationEngine` owns playback state.
`AnimationDriver` is a thin frame→map mapper. The old `JourneyEngine` /
`FrameRenderer` (custom video-frame pipeline) were left untouched.

## Timeline model

- `animation/AnimationPoint.kt` — stable `id` (source index in
  `resolvedPoints`), exact raw lat/lng/timestamp, optional city/country,
  `resolved` flag, and the original `ResolvedPoint` reference. Nothing is
  invented: no regions, country codes, activities, or durations.
- `animation/AnimationTimeline.kt` — built once on `Dispatchers.Default`:
  stable sort by timestamp, non-finite / non-Web-Mercator coordinates
  excluded from geography only (source data untouched), zero-distance hops
  become dwell segments.
- `AnimationSegment` — from/to points, true duration, compressed 1×
  duration, Haversine distance, dwell flag, cumulative start/end.
- Ordering key is the normalized timestamp (stable by source index);
  never city names, countries, GeoNames ids, or file order.

## Playback

`animation/AnimationEngine.kt`:

- States: IDLE → PLAYING ⇄ PAUSED → COMPLETED; STOPPED resets; ERROR holds.
- Monotonic-clock ticker (~30 FPS) in `viewModelScope`; speed scales
  animation time (0.25×–8×) without skipping segments.
- `play / pause / stop / restart / seekTo / seekToEvent / nextEvent /
  previousEvent / setSpeed / setFollowMode`.
- Seeking to the end sets COMPLETED; seeking elsewhere preserves the
  play/pause state; play from COMPLETED replays from the start.
- `advanceForTest()` gives deterministic, clock-free tests.
- `detach()` cancels the ticker; called on import replacement, failure,
  and `ViewModel.onCleared`. No leaked map references, timers, or
  coroutines — the engine never holds a `MapView`.
- Lifecycle: the screen pauses on `ON_PAUSE` (background or navigate
  away); foregrounding never auto-resumes. Position survives rotation via
  the retained ViewModel; process death is not covered (documented
  limitation).

## Camera

- `CameraFollowMode.FOLLOW` (default): `followAnimatedMarker` recenters
  only when the marker leaves the central viewport area (throttled to
  400 ms), so the camera glides instead of jumping every frame.
- Any user touch on the map switches to `FREE` (via the controller's
  user-interaction callback); the Recenter button resumes FOLLOW.
- Zoom is never forced during follow — the user's zoom is respected.

## Marker

- Distinct blue animated marker, lazily created and kept above the route
  and static markers; taps on it are consumed so static event dots stay
  tappable underneath.
- Position comes from great-circle slerp (`AnimationMath`), which handles
  antimeridian crossings the short way and arcs long-haul flights toward
  the pole. Endpoints are exact at fraction 0/1.

## Route

- `RouteDrawMode.PROGRESSIVE` (default): dim full route + emphasized
  dark-green traveled prefix. The prefix rebuilds only on segment
  boundaries/seeks; within a segment only the cursor point moves and the
  polyline's **live** list (`getActualPoints()` — `getPoints()` returns a
  deprecated copy) is mutated in place: no per-frame allocation.
- `RouteDrawMode.FULL`: classic full-route display, marker still moves.
- Switching modes hides/shows the traveled overlay; the static route and
  markers are never disturbed.

## Seeking

- Scrubber → `seekTo(fraction × totalMs)`; segment lookup is binary
  search, so seeks are O(log n) on any timeline length.
- Previous/Next jump by stable event id. From mid-segment, Previous goes
  to the event *before* the segment's start (standard "restart-or-back"
  behavior); Next goes to the segment's end event. At the ends, they
  clamp instead of wrapping.
- `seekToEvent(id)` ignores unknown ids; ids are source indexes, not
  chronological positions.

## Determinism

- `AnimationConfig` fixes target duration (90 s), per-segment min
  (600 ms) / max (8 s), and dwell (1.2 s). Compression is proportional to
  true durations, then clamped — same input always yields the same
  segment timings.
- `AnimationTimeline.stateAt(t)` is a pure function: animation time →
  frame state (marker lat/lng, display timestamp, event id, route
  progress). No wall clock inside. A future renderer can drive video
  frames through it deterministically.
- Display timestamps are linearly interpolated between segment
  endpoints — display only; source records are never rewritten.

## Files changed

New:

- `animation/AnimationConfig.kt`, `AnimationPoint.kt`,
  `AnimationMath.kt`, `AnimationSegment.kt`, `PlaybackState.kt`,
  `AnimationTimeline.kt`, `AnimationEngine.kt`
- `map/RouteDrawMode.kt`
- `ui/phase1/components/PlaybackControls.kt` (`AnimationPanel`)
- Tests: `animation/AnimationTimelineTest.kt` (22),
  `animation/AnimationEngineTest.kt` (15)

Modified:

- `map/InteractiveMapController.kt` — animation surface methods.
- `map/OsmMapController.kt` — animated marker, progressive route,
  follow/recenter camera, user-interaction callback, listener cleanup in
  `detach()`.
- `ui/phase1/Phase1ViewModel.kt` — builds the timeline after GeoNames
  resolution, owns the engine, `routeDrawMode` state, stale-engine
  cleanup on import failure.
- `ui/phase1/map/TimelineMap.kt` — controller hoisted to the caller.
- `ui/phase1/screens/PreviewScreens.kt` — hoisted controller,
  `AnimationDriver`, playback panel, lifecycle pause, stable-index
  detail dialog.
- `res/values/strings.xml` — animation labels.

## Tests

Pure-JVM Kotlin compiled with standalone `K2JVMCompiler` (Gradle is
impossible in the sandbox) and run under JUnit 4:

- **37/37 green**: `AnimationMathTest` (4), `AnimationTimelineTest`
  (18), `AnimationEngineTest` (15).
- Covers slerp endpoints/midpoint/antimeridian/long-haul, chronological
  stable ordering, duplicate timestamps, compression proportionality and
  min/max clamping, dwell segments, invalid-coordinate skipping,
  single-point timelines, arbitrary seeks and boundary behavior,
  display-time interpolation, speed scaling (0.5×–4×), pause stability,
  completion/replay/stop/reset, previous/next, seek state transitions,
  and deterministic `advanceForTest` runs.

## Build status

- Pure animation/model/controller logic: compiled and tested (see
  above).
- Full Gradle build / lint / debug APK: **not verifiable here** — the
  sandbox cannot run Gradle (loopback TCP corruption; see AGENTS.md).
  **Sara must build and run in Android Studio.**
- Android/Compose/osmdroid integration was statically audited only:
  delimiter balance, import usage, icon set (material-icons-core),
  `removeMapListener` API, and `Polyline.getActualPoints()` were
  verified against the 6.1.20 binary. Runtime map behavior, rotation,
  backgrounding, and performance are unverified.

## Known issues / limitations

1. Full Gradle build, lint, APK, and on-device verification must happen
   in Android Studio (sandbox limitation).
2. Playback position survives rotation (ViewModel) but not process
   death — no SavedStateHandle persistence yet.
3. `ON_PAUSE` pauses playback even when navigating between app screens;
   foregrounding never auto-resumes (intentional and safe).
4. Material icons were statically checked against the core set; a wrong
   pick would be a compile error in Android Studio, not a runtime risk.
5. Single-point journeys have total duration 0: the scrubber and play
   button disable, the marker sits on the point.
6. Timelines with no mappable coordinates show the "animation
   unavailable" note; original data is untouched.
7. `Polyline.getPoints()` in osmdroid returns a *copy* — the code uses
   `getActualPoints()` (verified present in 6.1.20). Future osmdroid
   upgrades should re-check this API.
8. Pre-existing Phase 2 parser quirks are unchanged (top-level array
   records uncollected, BOM/format-inspection order, 256 MB check after
   readBytes, progress on background dispatchers).

## Next phase

Recommended: **Phase 6 — Professional Video Preview & Video
Composition** (NOT implemented). The deterministic `stateAt(t)` design
is ready for it: a renderer can step the animation clock at fixed frame
intervals and composite marker/route/timestamp frames for MP4 export.
