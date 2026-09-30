# Release Checklist — Timeline Visualizer 0.3.0 (Phase 10)

**Package:** `com.journeyvisualizer.app` (internal; user-visible name is
"Timeline Visualizer")
**Version:** versionCode 3, versionName "0.3.0"
**Min SDK 26 · Target/Compile SDK 35 · JDK 17 · AGP 8.5.2 · Kotlin 2.0.21**

Nothing below is done automatically. Every item needs a human with Android
Studio and a real device.

## A. Engineering tasks (Android Studio required)

- [ ] **Build the project.** The codebase has never been compiled with Gradle
      (sandbox builds are impossible). Fix anything the compiler flags.
- [ ] **Run the full device pass** (§F below) on at least one real phone
      (Android 8 through 16 ideally). Emulator is not enough for the
      MediaCodec export path.
- [ ] **Add release signing.** Create a keystore, keep it OUT of the repo
      (it is gitignored by convention; `local.properties` currently holds only
      `sdk.dir`), wire a `signingConfigs` block reading the keystore path and
      passwords from `local.properties` or environment variables. Back the
      keystore up in two places — losing it means losing the Play listing.
- [ ] **Enable R8 + resource shrinking** for the release build type
      (`isMinifyEnabled = true`, `isShrinkResources = true`), then
      `./gradlew assembleRelease` and smoke-test the AAB: import → preview →
      export → playback → history → settings. Add `-keep` rules ONLY for
      actual breakage (no Gson/Moshi/serialization in the app; Room and
      osmdroid are the usual suspects).
- [ ] **Run lint** (`./gradlew lintRelease`) and fix real issues. Do not
      broadly suppress warnings.
- [ ] **Decide the public version.** `0.3.0` is a pre-1.0 dev version. Either
      ship `1.0.0` or keep `0.3.0` deliberately — bump `versionCode` every
      release.
- [ ] **Decide the applicationId question.** The internal package is still
      `com.journeyvisualizer.app` (renaming it without a verifiable build was
      judged too risky in Phase 10). It is invisible to users and fine for
      Play, but if you want `com.timelinevisualizer.app`, rename it BEFORE
      the first Play upload (it can never change afterwards).

## B. Play Console manual tasks

- [ ] Store listing: app name "Timeline Visualizer", short + full description
      (no claim the app makes that isn't true — see §D), screenshots for
      phone, feature graphic, category, contact email.
- [ ] Content rating questionnaire (no user-generated public content, no ads).
- [ ] **Data safety form:** location data is collected (Timeline import) but
      **never leaves the device** — declare "collected, not shared"; network
      use is map tiles only; no analytics/ads SDKs in the app.
- [ ] Privacy policy URL if required by the listing (the in-app Privacy
      screen already states the policy; a hosted copy may be needed).
- [ ] Internal-testing track first; promote only after the §F pass is green.

## C. Pre-publish verification (all manual)

- [ ] `./gradlew bundleRelease` produces a signed AAB; verify size and that
      the adaptive icon, label ("Timeline Visualizer"), and theme install
      correctly on a launcher.
- [ ] Cold start feels instant on a mid-range device (no heavy work in
      `MainActivity.onCreate` — verified statically; confirm on device).
- [ ] Export a 4K video on the target device BEFORE advertising 4K: the app
      probes the encoder and falls back honestly, but confirm the probe path
      on real hardware.
- [ ] Revoke `POST_NOTIFICATIONS` on Android 13+ and export — the foreground
      service must still run (notification permission only affects the tray
      icon, not the service).
- [ ] Airplane mode: import + preview + export with cached tiles works;
      fresh tile regions show the offline banner instead of hanging.

## D. Claims you may truthfully make

- "Your Timeline file never leaves your phone."
- "No account, no ads, no analytics."
- "The only network use is map tiles."
- "Videos are saved to Movies/Timeline Visualizer on your device."
- Do NOT claim: cloud backup/sync, region or country-code lookup (the
  bundled GeoNames data has name/lat/lng/country/population only), or
  background export via WorkManager (it is a foreground service).

## E. Known limitations (0.3.0)

- No instrumentation tests exist; Room, DataStore, MediaCodec/MediaMuxer,
  osmdroid, MediaStore, and the foreground service have only been statically
  audited, never executed.
- Release build is unoptimized until R8 is enabled (§A).
- Old exports in `Movies/Journey Visualizer` (pre-rename) remain playable via
  their stored MediaStore URIs; new exports go to `Movies/Timeline Visualizer`.
- First launch after install: notification channel "Video export" is created
  once; its name persists if the app is updated over an install that had it.

## F. End-to-end device pass (§38)

1. Fresh install → grant nothing → import the bundled sample (Sample button)
   → File Summary shows real stats → Timeline Preview shows the animated map.
2. Import a real `Timeline.json` (direct-array export): place names resolve
   offline; scrub/seek/speed/follow-camera all respond.
3. Video Preview: change aspect, resolution, fps, preset; validation blocks
   nonsense; export dialog runs the encoder preflight.
4. Export a 30 s 1080p video with the screen off → notification shows real
   progress → cancel mid-export leaves no corrupt file → completed MP4 plays
   in-app and in the system gallery.
5. My Videos: thumbnail appears, rename works, delete removes video then
   thumbnail then metadata, share sheet opens.
6. Settings: change theme (applies without restart), reset restores defaults,
   clear cache touches only app cache dirs, Privacy/Help/About/Licenses read
   correctly.
7. Rotate the phone during preview and during export; background the app
   during export; reboot mid-export (no zombie notification, no partial file
   kept).

## G. Release notes (0.3.0)

- Import Google Timeline JSON exports (direct-array and legacy semantic
  formats) with offline place-name resolution.
- Interactive animated map preview sharing one deterministic engine with
  the exporter.
- MP4 export (H.264, up to 4K/60fps where the device encoder allows) via a
  foreground service with progress, cancel, and safe failure handling.
- On-device video library with thumbnails, search, rename, share, delete.
- Settings, Privacy, Help, About, Licenses; system/light/dark theme.
- Privacy: everything on-device; map tiles are the only network use.
