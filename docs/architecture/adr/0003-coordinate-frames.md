# ADR 0003: coordinate frames and conversions

Status: accepted (2026-11-01, council #2)

## One owner

Every conversion between the frames below lives in **one file**:
`splat-core/src/main/kotlin/com/huntercoles/splatman/splat/frames/FrameConversions.kt` (object `FrameConversions`).
No other file flips an axis, reorders a quaternion or builds a projection from intrinsics. A review that finds a
`-y` or an `xyzw` outside that file sends it back.

## Notation

- `T_a_b` is the rigid transform that maps coordinates in frame `b` to frame `a` (`p_a = T_a_b * p_b`);
  `T_a_c = T_a_b * T_b_c`. A `CameraPose` is `T_world_camera`.
- Matrices are column-major `FloatArray(16)` (what `glUniformMatrix4fv(..., transpose=false)` takes), column vectors.
- Quaternions are Hamilton (`i*j = k`), active rotations, local -> parent. Inside the app they are always stored
  **w, x, y, z** (`Quat(w, x, y, z)`, `SplatCloud.rotations` as `[w,x,y,z]` per splat).

## Frames

| Name | Axes | Handedness | Units | Who uses it |
|---|---|---|---|---|
| **W** world (canonical) | ARCore world: +Y up (against gravity), X and Z horizontal, origin and yaw where the session started | right | m | `SplatCloud`, `SeedPoints`, every `CameraPose`, the trainer, the renderer's model space |
| **Cgl** camera, OpenGL axes | +X right, +Y up, looking down -Z; right/up relative to the **CPU image readout** (sensor orientation, not the screen) | right | m | ARCore `Camera.getPose()`, `CameraPose`, GL view matrix |
| **Ccv** camera, OpenCV axes | +X right, +Y down, +Z forward (looking direction) | right | m | pinhole projection, covariance Jacobian, trainer |
| **I** image | u right, v down, origin at the centre of the top-left pixel, pixel `(i,j)` centre at `(i,j)` | | px | `CameraIntrinsics`, keyframe images |
| **NDC/clip** | OpenGL: x right, y up, z into the screen in NDC, `[-1,1]^3` | left (NDC) | | shaders |
| **F** file (3DGS PLY, `.splat`, SPZ) | "RDF": +X right, +Y down, +Z forward, i.e. `W` turned 180 degrees about X | right | m | PLY, `.splat` and SPZ import/export |
| **glTF** | "RUB" = W's axes (+Y up), glTF quaternions x,y,z,w | right | m | glTF I/O (later) |
| **NS** Nerfstudio `transforms.json` | world = W; `transform_matrix` is `T_W_Cgl` (OpenGL camera axes, as Nerfstudio expects), row-major nested list | right | m | capture bundle |

## The conversions (exact)

1. **ARCore pose -> CameraPose.** `Pose.getTranslation()` = `t` (m); `Pose.getRotationQuaternion()` gives
   `[x, y, z, w]` -> `Quat(w, x, y, z)`. Use `Camera.getPose()` (sensor-oriented), never `getDisplayOrientedPose()`,
   together with `Camera.getImageIntrinsics()` and `Frame.acquireCameraImage()`; all three describe the same
   sensor-oriented image. Display rotation is a UI concern only.
2. **Cgl <-> Ccv.** `F = diag(1, -1, -1)`. `p_cv = F * p_gl`; `T_W_Ccv = T_W_Cgl * F` (rotation part `R * F`,
   translation unchanged). `F` is its own inverse.
3. **Projection (Ccv -> I).** `u = fx * X / Z + cx`, `v = fy * Y / Z + cy`, valid for `Z > 0`.
4. **GL view and projection.** `V = inverse(T_W_Cgl)`. For rendering an image's own view (training, AR overlay),
   the projection matrix from intrinsics (row r, column c), with near `n`, far `f`:
   `P[0][0] = 2fx/w`, `P[0][2] = 1 - 2(cx + 0.5)/w`, `P[1][1] = 2fy/h`, `P[1][2] = 2(cy + 0.5)/h - 1`,
   `P[2][2] = -(f + n)/(f - n)`, `P[2][3] = -2fn/(f - n)`, `P[3][2] = -1`, others 0. This maps pixel edges
   `u = -0.5` and `u = w - 0.5` to NDC x = -1 and +1 and the image top row to NDC y = +1. The orbit viewer uses its own
   field-of-view projection built in the same file.
5. **W <-> F (PLY, `.splat`, SPZ).** `G = diag(1, -1, -1)` (180 degrees about X, a proper rotation). Position
   `p_F = G * p_W`; quaternion `q_F = (w, x, -y, -z)` (conjugating by the X half-turn); log-scales and opacity
   unchanged; SH coefficients get one sign each (the basis function's parity under `y -> -y, z -> -z`):
   - degree 1 (`sh1..sh3` = y, z, x): `-, -, +`
   - degree 2 (`sh4..sh8` = xy, yz, 3z^2-1, xz, x^2-y^2): `-, +, +, -, +`
   - degree 3 (`sh9..sh15`): `-, +, -, -, +, -, +`
   - degree 0: unchanged.
   The map is its own inverse, so import uses the same function.
6. **W <-> glTF; SPZ quaternion order.** glTF: no axis change. SPZ is stored in F like PLY (rule 5; #154: tools
   that convert PLY to SPZ store the coordinates as they are, and the bundled cactus.spz matches the PLY cactus's raw
   F coordinates), so it takes rule 5's signs. glTF and SPZ quaternion component order (x, y, z, w) is handled by
   their codecs via `FrameConversions.quatToXyzw` / `quatFromXyzw`.
7. **W <-> NS.** `transform_matrix` = `T_W_Cgl` written row-major; intrinsics as `fl_x, fl_y, cx, cy, w, h`.

## Why W = ARCore world

It is what the tracker produces (no conversion on the hot path), it is gravity-aligned and metric, and it matches
OpenGL and glTF, so the renderer needs no flip either. The only flips are at file boundaries that demand them.

## Open checks

- The "PLY files are RDF" rule is a convention (COLMAP-derived trainers; Niantic's spz library documents it), not a
  spec. The viewer card's QA checks it on the real samples from #5: each must load upright in Splatman, and a
  Splatman-exported PLY must load upright in SuperSplat. If a sample contradicts it, the card stops and reports; it does not add a special case.
- ARCore does not document whether its principal point uses the pixel-centre or pixel-corner origin; the 0.5 px
  difference is below ARCore's own intrinsics error and is ignored.
