# Phase 7 — Deterministic Video Rendering & MP4 Export

Date: 2026-09-30. Status: implemented; pure-Kotlin layer verified 25/25
green on the JVM (118/118 incl. Phase 4–6 regression). Android/MediaCodec/
MediaMuxer/osmdroid integration is statically audited only — the sandbox
cannot run Gradle (see AGENTS.md); Sara builds and runs the app in Android
Studio, and only a physical-device run can verify encoding, playback, and
the configurations called "supported".

## What this phase is

A real, playable, silent MP4 rendered locally on the device from the real
timeline coordinates/timestamps, GeoNames metadata, Phase 5 animation
state, and Phase 6 composition settings:

Timeline JSON → parser → GeoNames → Phase 5 AnimationTimeline →
Phase 6 ExportSpec → RenderClock → AnimationTimeline.stateAt →
ExportCameraPlanner → CompositionFrameRenderer → EglCore →
hardware AVC → MediaMuxer → MP4 → Movies/Timeline Visualizer.

No second animation system: preview and export share one deterministic
state source — composition + frame timestamp → animation → camera →
route → marker → overlays. Frame time and presentation timestamps derive
from the frame index and FPS (RenderClock); never from sleeps, handlers,
UI playback, or wall-clock time. Export runs as fast as the device safely
permits; the one reusable ARGB frame bitmap is recycled, never the whole
sequence.

Out of scope (per authorization): History/My Videos redesign, advanced
sharing, editor/trimming, filters, transitions, music/audio, cloud
rendering, accounts, payments, ads, analytics.

## New code (all under `app/src/main/java/com/journeyvisualizer/app/export/`)

Pure Kotlin (JVM-tested):

- `RenderState.kt` — lifecycle: IDLE → PREPARING → RENDERING →
  FINALIZING → COMPLETED, with FAILED/CANCELLED from any active state.
  No PAUSED (a hardware-encoder drain loop cannot pause safely).
  `RenderProgress` derives fraction from completed frames; ETA/fps are
  measured, never estimated from timers.
- `RenderClock.kt` — integer frame count (rounds up), exact nanosecond
  PTS (frame 0 = 0 ns, no drift), video-clock ms per frame, and
  intro/journey/outro region mapping.
- `BitratePolicy.kt` — resolution-class bitrate heuristic scaled by fps,
  plus intersection with the codec's supported range.
- `RenderErrors.kt` — failure taxonomy with human-readable messages,
  incl. the exact "4K export is not supported on this device." wording.
- `ExportCameraPlanner.kt` — deterministic overview/follow/smart-follow
  camera; same viewport/throttle tuning as the Phase 6 preview
  (0.25/0.4 s, 0.40/0.8 s) but driven by video time. Stateful in frame
  order; replaying frames 0..N reproduces the identical camera path.
- `ExportFileNamer.kt` — `Timeline_yyyy-MM-dd_HHmm.mp4` + ` (2)`-style
  collision suffixes.

Android/MediaCodec (statically audited):

- `StyleTileCache.kt` — style-aware base/overlay tile cache (memory +
  disk, finite network timeouts). Encoding reads cached tiles only —
  no network on the hot path. Reports failed base-layer keys.
- `CompositionFrameRenderer.kt` — Canvas renderer: tiles, route modes,
  animated/start/end markers, attribution, overlays, safe areas,
  intro/outro, progress. Scaled typography, no app/system controls.
- `EncoderProbe.kt` — checks the real AVC encoder: surface input,
  size/rate support, bitrate range; suggests a same-aspect fallback.
- `OutputValidator.kt` — nonzero file, readable metadata, duration and
  dimension checks via MediaMetadataRetriever.
- `ExportProgressBus.kt` — in-process observable of state/progress/last
  completed render; the screen re-attaches after rotation/navigation.
- `Phase7RenderRequest.kt` — carries the live AnimationTimeline + the
  Phase 6 ExportSpec + timeline file-name reference (never the JSON).

Changed:

- `VideoExporter.kt` — new `exportPhase7()` (legacy `export()` path
  untouched): validation → encoder preflight → deterministic camera
  simulation → tile prefetch → missing-tile coverage policy → hardware
  AVC config → one reusable bitmap → deterministic PTS → EOS drain
  (bounded: a driver that never emits EOS fails instead of hanging) →
  muxer stop → measured progress. `EncoderDrainer` is the shared
  drain; the legacy loop keeps its own.
- `ExportService.kt` — `ACTION_START_PHASE7`/`ACTION_CANCEL_PHASE7`,
  `enqueuePhase7()` (duplicate protection: pending-request + bus
  checks), unique MediaStore filename, finalize-then-verify
  (`OutputValidator`) before reporting COMPLETED, cancellation deletes
  the partial file and reports CANCELLED. Legacy path untouched.
- `VideoRepository.kt` — `displayNameExists()`; `abortPendingVideo()`
  deletes pre-29 `file://` outputs directly.
- `Phase1ViewModel.buildPhase7Request()` — assembles the request from
  the live engine + current composition (validation-gated).
- `VideoPreviewScreen` — the Phase 7 placeholder dialog is now a real
  export dialog: spec summary + encoder preflight + fallback offer +
  start; plus a progress dialog (state text, frame counts, measured
  fps/ETA, cancel) that re-attaches to an in-flight render.
- `VideoExportResultScreen` (new) — platform `VideoView` playback (the
  app's single player surface), filename/duration/resolution/fps/size,
  basic ACTION_SEND share. Route `export_result` in `Phase1Nav`.
- `strings.xml` — export dialog/progress/result/notification strings;
  the old "Ready for Phase 7" placeholder copy is gone.

## Key behaviors

- Preflight: codec, dimensions, FPS, bitrate, profile/level where
  relevant, memory risk, composition, timeline, storage, and map/tile
  readiness are checked before encoding starts. Unsupported 4K fails
  with the exact required wording.
- Tiles: never silently encode blank/loading/missing tiles. Prefetch
  has finite timeouts; if any frame would miss >50% of its base map or
  the mean miss rate exceeds 15%, the render fails with a useful
  message instead of producing a misleading video.
- Cancellation: stops new frames, shuts down encoder/renderer safely,
  deletes the incomplete output, reports CANCELLED.
- Storage: unique filenames, modern MediaStore APIs (`Movies/Journey
  Visualizer`), no broad permissions; completed videos persist after
  app closure. All generation is local — nothing is uploaded.
- Silent video. No music, no audio track.
- Duplicate exports: an identical/active export cannot start twice
  (pending-request + bus + service-side guards).

## Verification

- 25 new JVM tests (`Phase7DeterminismTest`): frame math/PTS,
  intro/journey/outro mapping, bitrate policy, error taxonomy,
  filename uniqueness, camera determinism (incl. a known
  Lahore → Islamabad → Murree → Skardu route), follow tuning parity
  with the preview, and frame-timestamp → animation-state replay
  equality. **25/25 green.**
- Phase 4+5+6 pure suites re-run: **118/118 green total.**
- Static Android audit: exporter structure (single companion object,
  legacy loop re-attached and untouched), service lifecycle,
  notification actions, Manifest (mediaProcessing FGS already
  declared), string resources, navigation route, ViewModel wiring.
- NOT verified (needs Sara's Android Studio + device): Gradle build,
  lint, APK, MediaCodec encoding, real MP4 output, playback, 720p/
  1080p/portrait/landscape spot checks, cancellation/failure
  recovery, backgrounding, storage, large timelines, no-network
  behavior, memory. Only configurations genuinely verified on a
  device may be called supported.

## Known limits

- Process death mid-export loses the in-memory timeline (the full
  imported AnimationTimeline is not persisted); the pending MediaStore
  entry stays invisible (`IS_PENDING=1`). The UI re-attaches across
  rotation/navigation, not across process death.
- Integer tile zoom: overview zoom truncates to whole levels (tiles
  are integer-zoom), so portrait/landscape can pick the same zoom on
  routes that fit either.
- 60 FPS and 4K are offered by the composition but only genuinely
  supported where the device's encoder reports support; anything else
  fails fast with guidance.
