# Status

Now (2026-11-02): v0.0.1, a prototype that is about to be rebuilt by the Company. The gate (`tools/check.sh`:
assembleDebug, unit tests, lint) is green; detekt is not part of it (69 findings).
- Every unit test is JUnit 5 (Jupiter) and runs in the gate: the JUnit 4 ones were ported, capture-feature applies the android-junit5 plugin (#54).
- The gate counts the unit tests (`unit tests: N`) and goes red at 0 or under the floor in `tools/test-count.min` (69; raise it on purpose) (#59).
- Poker-app leftovers (blinds, chips, odds, bank, tournament, timer) are deleted (#6); the Library nav route renders `LibraryScreen`.
- Design tokens (#12): `core/design` holds the v1 violet dark/light M3 schemes, overlay tokens, full type scale plus
  `numeral`, trimmed `SplatDimens`, `SplatMotion`, and one `SplatTheme(forceDark, darkTheme)`.
- App shell (#13): no bottom nav; typed routes in `core/navigation/NavigationDestination` (`onboarding`, `library`,
  `capture`, `processing/{scanId}`, `viewer/{scanId}`, `settings`, `about`), rules in `ShellRoutes`; host in
  `app/shell/SplatmanShell.kt` (edge-to-edge, Capture/Processing/Viewer dark, others follow `ThemePreferences.themeMode`).
  Onboarding on first run (`OnboardingPreferences`), else Library. Every route but Library and Viewer is a placeholder.
- UI and product spec: `docs/design/` (v1 features, flows, every screen, tokens, copy); build tickets #12-#21.
- Renderer: OpenGL ES, in `viewer-feature` (`GaussianCameraController`, now on `splat-core` math); camera capture works.
- GPU packer (#35): `viewer-feature/rendering/pack/SplatPacker` packs a `SplatCloud` into the ADR 0006 RGBA32UI layout
  (1M splats in ~0.4 s on the JVM); not yet drawn (#38).
- `splat-core` kernels (#27): `sh/ShEval` (3DGS SH to degree 3), `projection/Covariance` (world + EWA project),
  `sort/DepthSort` (16-bit counting sort, back to front); not yet wired into the renderer or trainer.
- `splat-core` `frames/FrameConversions` (#28, ADR 0003): ARCore pose, xyzw order, Cgl/Ccv, view and
  intrinsics projection, pinhole, W <-> PLY file frame (positions, quaternions, SH signs, whole cloud), Nerfstudio.
- CPU rasteriser forward (#37): `splat-train/raster/Rasterizer.forward` (16x16 tiles, front-to-back, deterministic
  over threads; 50k splats at 320x240 in ~19 ms on the desktop) is the golden renderer. Backward (#40):
  `Rasterizer.backward` fills `SplatGrads` (positions, log-scales, raw quaternions, opacity logits, SH dc, NDC view
  gradient norm), proven by finite differences in Double (`RasterizerGradientTest`); no optimiser yet (#42).
- Tracking (#36, ADR 0004): `capture-feature/tracking/` has the `PoseSource` seam, `TrackedFrame`, `TrackerDriver`
  (availability and permission before any session call, lifecycle pause/resume, dropped frames closed), `Yuv420`;
  `tracking/arcore/` holds ARCore 1.57.0 (optional at install) and `ArCorePoseSource`; no screen uses it yet (#39).
- Splat pipeline: `splat-train` has the `Trainer` interface and `SeedTrainer` (seed points -> 3DGS seed splat, #30);
  nothing yet turns frames plus ARCore poses into seed points, and there is no export.
- Real CC0 splat samples live in `app/src/main/assets/samples/` (cactus PLY SH1, measuring-tape PLY SH0, cactus
  SPZ; see its ASSETS-LICENSES.md); the old goat point cloud is gone. Nothing loads them in the app yet.
- The new data model (ADR 0002) is in `splat-core` (`splat/model`: `SplatCloud`, `CameraIntrinsics`, `Keyframe`, ...;
  `splat/packet`: training packets, `ExportFormat`, #26); the old point-cloud model in the app modules is unused by it.
- Much of `core`, `capture-feature` and `library-feature` is leftover poker-app code (blinds, chips, bank, odds);
  it is dead weight to delete.
- Build fix made when setting up the gate: removed `jvmToolchain(17)` from the modules (only JDK 21 is installed;
  bytecode still targets 17).
- F-Droid metadata no longer lists an author website (it named a private host).
