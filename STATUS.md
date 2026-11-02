# Status

Now (2026-11-01): v0.0.1, a prototype that is about to be rebuilt by the Company. The gate (`tools/check.sh`:
assembleDebug, unit tests, lint) is green; detekt is not part of it (69 findings).
- UI and product spec: `docs/design/` (v1 features, flows, every screen, tokens, copy); build tickets #12-#21.
- Renderer: OpenGL ES, in `viewer-feature` (`GaussianCameraController`); camera capture works.
- No real splat pipeline yet: nothing turns frames plus ARCore poses into Gaussians, and there is no export.
- Real CC0 splat samples live in `app/src/main/assets/samples/` (cactus PLY SH1, measuring-tape PLY SH0, cactus
  SPZ; see its ASSETS-LICENSES.md); the old goat point cloud is gone. Nothing loads them in the app yet.
- The data model was built around point clouds and is to be redesigned for Gaussians.
- Much of `core`, `capture-feature` and `library-feature` is leftover poker-app code (blinds, chips, bank, odds);
  it is dead weight to delete.
- Build fix made when setting up the gate: removed `jvmToolchain(17)` from the modules (only JDK 21 is installed;
  bytecode still targets 17).
- F-Droid metadata no longer lists an author website (it named a private host).
