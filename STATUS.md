# Status

Now (2026-11-01): v0.0.1, a prototype that is about to be rebuilt by the Company. The gate (`tools/check.sh`:
assembleDebug, unit tests, lint) is green; detekt is not part of it (69 findings).
- Renderer: OpenGL ES, in `viewer-feature` (`GaussianCameraController`); camera capture works.
- No real splat pipeline yet: nothing turns frames plus ARCore poses into Gaussians, and there is no export.
- The sample asset `app/src/main/assets/models/Goat skull.ply` is a plain point cloud, not a 3D Gaussian splat
  (no scale, rotation, opacity or spherical-harmonics fields).
- The data model was built around point clouds and is to be redesigned for Gaussians.
- Much of `core`, `capture-feature` and `library-feature` is leftover poker-app code (blinds, chips, bank, odds);
  it is dead weight to delete.
- Build fix made when setting up the gate: removed `jvmToolchain(17)` from the modules (only JDK 21 is installed;
  bytecode still targets 17).
- F-Droid metadata no longer lists an author website (it named a private host).
