# Status

Now (2026-11-02): v0.0.1, a prototype that is about to be rebuilt by the Company. The gate (`tools/check.sh`:
assembleDebug, unit tests, lint) is green; detekt is not part of it (69 findings).
- `splat-gpu` (#101): GLES 3.1 compute plumbing (`GlesCompute` on one GL thread, `Ssbo`, `ComputeProgram`, `Dispatch`, `Std430`) and `GpuSupport.probe()`; JVM tests in the gate, the on-device smoke test is `./gradlew :splat-gpu:connectedDebugAndroidTest`.
- Every unit test is JUnit 5 (Jupiter) and runs in the gate: the JUnit 4 ones were ported, capture-feature applies the android-junit5 plugin (#54).
- The gate counts the unit tests (`unit tests: N`) and goes red at 0 or under the floor in `tools/test-count.min` (69; raise it on purpose) (#59).
- Mutation testing (#66): `tools/check.sh :splat-core:pitest` (pitest 1.17.1, Gradle plugin 1.15.0, JUnit 5 engine
  1.2.1; not in the gate), manual forge workflow `mutation`. First score (2026-11-02, desktop): 1356/1577 mutants
  killed (86 %), test strength 89 %, line coverage 92 %; reports in `splat-core/build/reports/pitest/`.
- Poker-app leftovers (blinds, chips, odds, bank, tournament, timer) are deleted (#6); the Library nav route renders `LibraryScreen`.
- Library (#46): scans are Room `ScanEntity` rows (DB `splat_database` v2) over folders `filesDir/scans/<id>/` (ADR 0007); `ScanRepository` syncs, renames, deletes (cancelling processing via `ProcessingCanceller`, a no-op until #43); samples open the Viewer as `sample:<file>`. The old point-cloud path is gone.
- Recording (#41): Capture has Record/Stop/Cancel; `capture/recording/CaptureRecorder` takes keyframes (luma sharpness + `KeyframeSelector`), 640x480 JPEG q90, seeds from points and depth, and on Stop writes the bundle to `filesDir/recording/<id>/` then renames it to `filesDir/scans/<id>/` (the library adopts it); Cancel keeps nothing.
- Design tokens (#12): `core/design` holds the v1 violet dark/light M3 schemes, overlay tokens, full type scale plus
  `numeral`, trimmed `SplatDimens`, `SplatMotion`, and one `SplatTheme(forceDark, darkTheme)`.
- App shell (#13): no bottom nav; typed routes in `core/navigation/NavigationDestination` (`onboarding`, `library`,
  `capture`, `processing/{scanId}`, `viewer/{scanId}`, `settings`, `about`), rules in `ShellRoutes`; host in
  `app/shell/SplatmanShell.kt` (edge-to-edge, Capture/Processing/Viewer dark, others follow `ThemePreferences.themeMode`).
  Onboarding on first run (`OnboardingPreferences`), else Library. Every route but Library, Viewer, Capture and Onboarding is a placeholder.
- Onboarding (#14): `capture-feature/presentation/onboarding/` (three-page pager, Skip, dots, camera permission, AR
  unsupported / install pages from `ArCoreAvailability`); Settings > Show scanning tips again opens `Onboarding(page = 1)`.
- UI and product spec: `docs/design/` (v1 features, flows, every screen, tokens, copy); build tickets #12-#21.
- Viewer (#38): GLES 3.0 `rendering/gl/SplatRenderer` (RGBA32UI texture, instanced EWA quads, worker-thread
  `DepthSort` into a double-buffered order VBO, 0.5-1.0 render scale, rebuilt on context loss) behind a `Gl` seam;
  `ViewerScreen` opens the `sample:<file>` the Library passed, the bundled samples or a picked PLY/.splat
  (`presentation/load`; `SplatSanity` refuses non-finite or out-of-bounds clouds before framing), fps overlay; scan ids are #18. Shaders are only checked on a device (QA). The viewer's
  CameraX preview is gone (capture owns the camera).
- Export (#45): the viewer's Export chip opens `viewer/export/ExportSheet`: PLY (default) or .splat (SPZ once #34 adds
  a writer), Save via SAF `CreateDocument`, Share via `ACTION_SEND` and the app's FileProvider (`<applicationId>.exports`,
  only `cache/exports/`), MIME `application/octet-stream`, file `<scan name>.<ext>` sanitised; `ExportUseCase` writes
  off the main thread with progress and error states.
- GPU packer (#35): `viewer-feature/rendering/pack/SplatPacker` packs a `SplatCloud` into the ADR 0006 RGBA32UI layout
  (1M splats in ~0.4 s on the JVM); drawn by #38.
- `splat-core` kernels (#27): `sh/ShEval` (3DGS SH to degree 3), `projection/Covariance` (world + EWA project),
  `sort/DepthSort` (16-bit counting sort, back to front); not yet wired into the renderer or trainer.
- `splat-core` `frames/FrameConversions` (#28, ADR 0003): ARCore pose, xyzw order, Cgl/Ccv, view and
  intrinsics projection, pinhole, W <-> PLY file frame (positions, quaternions, SH signs, whole cloud), Nerfstudio.
- Capture bundle (#33, ADR 0007): `splat-io/bundle` `CaptureBundleWriter`/`CaptureBundleReader` write and read
  `transforms.json` (Nerfstudio + `splatman_version`), JPEGs, f32 depth and `seed.ply`, each file temp + rename and
  `transforms.json` last; nothing records a capture into it yet (#41).
- SPZ (#34, ADR 0007): `splat-io/spz/SpzCodec` reads v2/v3 and writes v3 (Niantic reference quantisation, RUB = W),
  streamed through one 64 KiB buffer (1M splats decode in ~1 s); no export menu uses it yet.
- CPU rasteriser forward (#37): `splat-train/raster/Rasterizer.forward` (16x16 tiles, front-to-back, deterministic
  over threads; 50k splats at 320x240 in ~19 ms on the desktop) is the golden renderer. Backward (#40):
  `Rasterizer.backward` fills `SplatGrads` (positions, log-scales, raw quaternions, opacity logits, SH dc, NDC view
  gradient norm), proven by finite differences in Double (`RasterizerGradientTest`); no optimiser yet (#42).
- Lens refinement (#10): `raster/Lens` (one focal scale on fx, fy plus k1) in forward and backward (lens gradients
  proven by finite differences); `SplatOptimizer` learns it after `OptimSettings.cameraFreezeSteps` (500), Adam,
  clamped to 1 +- 0.1 and +- 0.1, off with `refineCamera = false`; `TrainingProgress.camera` / `TrainingResult.camera`
  (`CameraRefinement`) report it. Synthetic test: 5 % focal error -> 0.46 %. Needs captures from two distances: from
  one orbit distance focal vs scene scale is nearly ambiguous and the lens can drift to its clamp.
- Tracking (#36, ADR 0004): `capture-feature/tracking/` has the `PoseSource` seam, `TrackedFrame`, `TrackerDriver`
  (availability and permission before any session call, lifecycle pause/resume, dropped frames closed), `Yuv420`;
  `tracking/arcore/` holds ARCore 1.57.0 (optional at install) and `ArCorePoseSource`.
- Capture screen (#39): `presentation/capture/` (`CaptureScreen`, `CaptureViewModel`, `CaptureTracker` seam) on the
  Capture route, portrait-locked; the tracking thread draws the camera texture into a `SurfaceView`
  (`preview/CameraBackgroundRenderer`, `transformCoordinates2d`) and the hint follows the frame's `TrackingFailure`.
  Plain Material 3 until #4 restyles it; no recording yet (#41).
- Splat pipeline: `splat-train` has the `Trainer` interface and `SeedTrainer` (seed points -> 3DGS seed splat, #30);
  nothing yet turns frames plus ARCore poses into seed points, and there is no export.
- Train backend seam (#100): `SplatOptimizer` keeps the schedule and drives a `TrainBackend` (`optim/TrainBackend.kt`:
  load, step, read, `TrainState`, `StepRates`); `CpuBackend` is the reference, bit-identical (golden CRC in
  `TrainBackendTest`). The GPU backend (#49) plugs in there.
- Real CC0 splat samples live in `app/src/main/assets/samples/` (cactus PLY SH1, measuring-tape PLY SH0, cactus
  SPZ; see its ASSETS-LICENSES.md); the old goat point cloud is gone. Nothing loads them in the app yet.
- The new data model (ADR 0002) is in `splat-core` (`splat/model`: `SplatCloud`, `CameraIntrinsics`, `Keyframe`, ...;
  `splat/packet`: training packets, `ExportFormat`, #26); the old point-cloud model in the app modules is unused by it.
- Much of `core`, `capture-feature` and `library-feature` is leftover poker-app code (blinds, chips, bank, odds);
  it is dead weight to delete.
- Build fix made when setting up the gate: removed `jvmToolchain(17)` from the modules (only JDK 21 is installed;
  bytecode still targets 17).
- F-Droid metadata no longer lists an author website (it named a private host).
