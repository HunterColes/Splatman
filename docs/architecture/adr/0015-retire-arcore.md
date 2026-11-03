# ADR 0015: retire ARCore and the full flavour; foss is the build

Status: accepted (2026-11-03, epic #342, from Hunter's direction in #335). Supersedes in part
[ADR 0004](0004-tracking.md) (ARCore as the tracker) and the `full`/`foss` flavour split of #48; supersedes the
"adopt ARCore (optional)" line of [ADR 0008](0008-libraries.md).

## Context

Hunter's direction (3 Nov, #335): Splatman goes de-Google and open source. No ARCore, no Play Services, no
proprietary trackers. Vuforia was considered and ruled out (closed SDK, licence key, and on Android it leans on
ARCore). His phone is a GrapheneOS Pixel 8 Pro without Play Services, so the `full` flavour cannot scan on it at all.

The `foss` path already records a pose-free scan (frames, IMU and Camera2 calibration, epic #199) and gets camera
positions after capture from open-source SfM ([ADR 0012](0012-foss-pose-source.md), the chain #225, #226, #228,
#229, #230). Our own visual-inertial odometry is a later epic (#336, ADR 0016), stage 2 of which may fill the
`PoseSource` seam live. Until then nothing in the tree produces a `TrackedFrame`.

## Decision

1. **One build, no flavours.** The foss path is the product. The `full` flavour goes first; then the flavour
   dimension itself, so `src/foss` becomes `src/main` and the APK is `Splatman-v<version>-release.apk`. The
   dependency guard (`VerifyFossDependenciesTask`: no `com.google.ar`, `com.google.android.gms`, `com.google.firebase`
   or `com.android.billingclient`) stays and runs on every variant.
2. **What goes**, by package or file:
   - the `full` flavour: `capture-feature/src/full/` (`tracking/FlavourTracking.kt`, `tracking/arcore/`
     `ArCoreAvailability`, `ArCorePoseSource`, `ArCoreTrackerSession`, `OffscreenGl`, its `AndroidManifest.xml`),
     `capture-feature/src/testFull/`, the `full` entries and full* task aliases in `app/build.gradle.kts` and
     `capture-feature/build.gradle.kts`;
   - the `com.google.ar` dependency: `arcore` in `gradle/libs.versions.toml` and `fullImplementation(libs.arcore)`;
   - `ArCoreAvailability` and the onboarding AR pages: the install page, the couldn't-check page,
     `TrackingAvailability.INSTALL_NEEDED` and `COULDNT_CHECK`, `TrackerSupport.requestInstall`, the `ar_install_*`
     and `ar_check_failed_*` strings, Capture's install offer;
   - the ARCore tracking and the `PoseSource` implementation: `ArCorePoseSource`, `TrackerDriver`, `FrameParts`,
     `CameraBackgroundRenderer`, `CaptureTracker` and its Hilt module, the foss stubs `UnavailablePoseSource`,
     `UnavailableCaptureTracker` and `NoTrackerSupport`;
   - `CaptureRecorder`'s ARCore path (recording `TrackedFrame`s into a bundle with seed points) and
     `RecordingModule`; `CaptureRecorder.meanLuma`, which the foss recorder uses, moves to `recording/Luma.kt`;
   - `CaptureViewModel`'s tracked branch (`trackedUiState`, `record()`, the subject tap and reticle, the `poseFree`
     flag) and `Optional<PoseFreeRecorder>`: the recorder becomes a required binding;
   - e2e on `fullDebug`: `tools/e2e.sh` and the device tests run the foss build, and the capture test runs on the
     emulator's back camera instead of being skipped for no ARCore.
3. **What the foss path keeps**: `Coverage`, `GyroCoverage`, `RoomCoverage`, `RoomMotion`, `PoseFreeGuidance`,
   `Guidance` and `WarningArbiter`, the Capture overlay (`CaptureOverlay`, `CaptureScreen`), `MediaCameraImage` and
   `Yuv420`, and in `splat-core` `KeyframeSelector`, `PoseFreeKeyframeSelector` and `SeedAccumulator`. `PoseSource`
   and `TrackedFrame` stay, with no implementation, as the seam #336 fills; their KDoc says so. `splat-core`,
   `splat-io` and `splat-train` are not touched: `FrameConversions.poseFromArCore` and the frame W keep their maths
   (right-handed, +Y up, gravity-aligned, metres), as named conversions, not a dependency.
4. **Order.** The removal waits for the SfM chain: no removal card touches its files (`settings.gradle.kts`,
   `gradle/libs.versions.toml`, both `build.gradle.kts`, `tools/e2e.sh`, `capture-feature/src/foss/`, ADR 0012,
   `splat-sfm/`) before #230 closes. The cards, each at most about 400 hand-written lines (whole-file deletions
   aside), in order:
   1. #343 e2e and device tests on `fossDebug` (after #230, #341);
   2. #344 drop the `full` flavour and the ARCore dependency;
   3. #345 `CaptureViewModel` pose-free only (after the Room-mode cards below and #340);
   4. #346 remove the tracked recorder and tracker plumbing; keep `PoseSource` and `TrackedFrame`;
   5. #347 onboarding without AR pages;
   6. #348 collapse the flavours (after #340);
   7. #349 docs sweep: CLAUDE.md layout, architecture README, design docs, store text, README.
5. **The Room-mode cards built on the ARCore recorder:**
   - #289 (CaptureRecorder records a room): close without merging PR #320; the tracked `CaptureRecorder` goes.
     `RoomCoverage` and `RoomMotion`, already on master, stay for foss Room mode.
   - #300 (Capture ViewModel Room state, warnings, Finish rule): re-aim at foss: the same state, warnings and
     Finish rule on the pose-free path, taking the ViewModel part of #292.
   - #291 (Capture UI Room mode): re-aim at foss: the switch, gauge, large view without the walked path and the
     room strings, taking #292's overlay differences; after #300.
   - #292 (foss Room mode): keep, narrowed to the recorder side (`PoseFreeRecorder.start(mode)`, mode in
     capture.json, gyro Seen estimate, no-gyroscope, 120-frame minimum).

## Consequences

- One build to test, ship and describe; F-Droid builds the plain release.
- Until #336 stage 2 lands there is no live 6-DoF pose: guidance is gyro-driven, and camera positions exist only
  after SfM. A tracked recording path, if stage 2 wants one, is rebuilt on `PoseSource` from history, not kept dead.
- The ARCore-era ADRs (0003, 0004, 0008) are history; read this ADR for what holds.

## Rejected

- Keeping `full` as an optional side flavour: it cannot scan on Hunter's phone, doubles the gate and the e2e, and
  keeps a proprietary dependency in the tree.
- Vuforia or another closed tracker: closed SDK, licence key, ARCore underneath on Android.
- A lone `foss` flavour kept forever: flavour machinery with one value is noise; the guard runs per variant instead.
