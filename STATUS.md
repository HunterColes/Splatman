# Status

Now (2026-11-02): v0.0.1, a prototype that is about to be rebuilt by the Company. The gate (`tools/check.sh`:
assembleDebug, unit tests, lint) is green; detekt is not part of it (69 findings).
`tools/e2e.sh` (headless emulator smoke test, screenshots in build/e2e/) is green: OK (2 tests), capture skipped (no ARCore).
- End to end (#47): Capture's Stop saves the scan and opens `processing/{scanId}` (Capture leaves the back stack); `presentation/processing/ProcessingScreen` starts the foreground runner for an unprocessed scan, shows progress, Cancel and Try again, and on Done opens `viewer/{scanId}` (Processing leaves the back stack), which loads `scans/<id>/splat.ply`. Hops in `core/navigation/ScanFlow`. The runner's starts and ends update the library rows (`ScanStatusSync`, wired in `app/pipeline`); after a restart a row left PROCESSING reads FAILED and Processing offers Try again. Library delete cancels the run first.
- Forge runner (`deploy/forgejo-runner/`, #60, #74): one job per container start (`forgejo-runner one-job`); credentials in a root-only volume, jobs as uid 1001 with no capabilities; the whole Gradle user home emptied before every job (#133: no job loads a dist or jar an earlier one left; every build downloads them again). Re-register once after #74 (README step 3).
- `splat-gpu` (#101): GLES 3.1 compute plumbing (`GlesCompute` on one GL thread, `Ssbo`, `ComputeProgram`, `Dispatch`, `Std430`) and `GpuSupport.probe()`; JVM tests in the gate, the on-device smoke test is `./gradlew :splat-gpu:connectedDebugAndroidTest`.
- GPU forward 1 (#102): `splat-gpu/.../raster`: `GpuCloud` (one SoA SSBO, `CloudLayout`), the projection kernel (`ProjectShaders`) and binning (`GpuBinning`: count, scan, emit (tile, depth bits) keys, stable 8-bit LSD radix sort `GpuRadixSort`, tileStart) into `GpuRenderBuffers`; JVM tests model the scan and sort; on-device parity with the CPU is `ProjectionParityTest` (not yet run on a phone).
- GPU forward 2 (#104): `GpuRasterizer.forward` (projection, binning, then `CompositeShaders`: one 16x16 workgroup per tile, 256-splat batches in shared memory, early exit once every pixel stopped) into a GPU-resident `GpuFrame` (`FrameLayout` image + contributors); `GpuFrame.readInto` is for tests only. `CompositeModelTest` models the kernel bit-exact against the CPU; on-device parity is `ForwardParityTest` (not yet run on a phone).
- GPU backward 1 (#105): `GpuGrads2d.run(frame, dLdColor)`: `BackwardTileShaders` PACK (per-splat record), REPLAY (one 16x16 workgroup per tile, back-to-front lockstep, per-entry fixed-order shared-memory sum into entryGrads) and GATHER (per splat, rect walk plus binary search on (depth bits, index), no atomics) into splatGrads2d. `Grads2dModelTest` models them against a linear scan and the CPU; on-device parity is `Backward2dParityTest` (not yet run on a phone). `SplatGrads.screenGrads2d()` is a test accessor.
- GPU backward 2 (#106): `GpuRasterizer.backward(cloud, k, pose, frame, dLdColor, out: GpuGrads)` (pinhole): GpuGrads2d, then `BackwardSplatShaders.SPLAT` (per splat, `RasterizerBackward.splatBackward` in fp32: conic -> cov2d -> cov3d -> scales and quaternion, means through the pinhole and the pose, sigmoid, C0, viewGradNorms in NDC) into one `GradsLayout` buffer (the cloud's sections, then viewGradNorms). `SplatBackwardModelTest` models it against the CPU; on-device parity is `BackwardParityTest` (not yet run on a phone). `GpuGrads.readInto(SplatGrads)` is for tests; `SplatGrads.ensureSplats` is public for it.
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
  Onboarding on first run (`OnboardingPreferences`), else Library. Processing is still a placeholder.
- Onboarding (#14): `capture-feature/presentation/onboarding/` (three-page pager, Skip, dots, camera permission, AR
  unsupported / install pages from `ArCoreAvailability`); Settings > Show scanning tips again opens `Onboarding(page = 1)`.
- Settings and About (#19): `library-feature/settings/` (`SettingsFeature`, `AboutFeature` with an in-screen licences
  list); preferences `ThemePreferences` and `SettingsPreferences` (haptics, keep frames, export format; not yet read by
  Capture or Export) over the `PreferenceStore` seam; `data/storage/ScanStorage` counts `scans/` and clears built
  scans' `images/`. `app/build.gradle.kts` task `generate<Variant>Licences` writes `licences.tsv` into the assets from
  the runtime classpath's POMs. Poker audio (`AudioPreferences`, `SoundManager`) and the viewer's Tools/Settings are gone.
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
- SPZ (#34, ADR 0007): `splat-io/spz/SpzCodec` reads v2/v3 and writes v3 (Niantic reference quantisation, stored in F like PLY, #154),
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
