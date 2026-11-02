# Architecture council (ticket #2, 2026-11-01)

Six seats, each told to argue its own position and attack the others. Two rounds (positions, cross-examination),
then a written consensus with the dissents kept. The council ran inside one architect session (no separate
subagent processes were available to that run); each seat argued in turn. The ADRs in `adr/` hold the results.

Seats: **R** 3DGS research scientist, **G** mobile graphics engineer, **S** AR/SLAM engineer, **D** data/format
engineer, **A** Android architect, **X** sceptic ("what will not work tonight").

## Round 1: positions

**R (research).** A splat is not a point cloud. Each primitive carries a mean, a log-scale per axis, a unit
quaternion, a logit opacity and SH coefficients; the optimiser works on those raw parameters, so store them raw
and activate (exp, sigmoid, normalise) only where you use them. Training wants degree-3 SH, 30k iterations,
densification and SSIM. A phone can't do 30k iterations of gsplat-quality training on a full scene, so the honest
answer is a self-hosted GPU server running gsplat, with on-device training as a toy.

**G (graphics).** Rendering is the easy part and well understood: sort the splats back to front by view depth,
draw one screen-aligned quad per splat sized from its projected 2D covariance, alpha-blend premultiplied. This is
what antimatter15/splat, gsplat.js and PlayCanvas do in WebGL 2, which is GLES 3.0. Do it in GLES 3.0 with splat data
in textures (`texelFetch`), a CPU radix sort on a worker thread, and an index buffer. Vulkan with GPU radix sort is
faster at 2M+ splats, but it is a second renderer and nothing in the repo uses Vulkan. Budget: 60 fps at 300k
splats on a 2022 mid-range Adreno/Mali, 30 fps at 1M.

**S (AR/SLAM).** ARCore gives us metric, gravity-aligned 6-DoF poses at 30 Hz, intrinsics for the CPU image, a
sparse feature point cloud and (on supported phones) depth. That replaces COLMAP entirely: no SfM on the phone. The
traps are frames. `Camera.getPose()` is an OpenGL-convention camera (+X right, +Y up, looking down -Z) relative to
the sensor's image readout, not the screen; `getDisplayOrientedPose()` is rotated to the display. Pair
`getPose()` with `getImageIntrinsics()` and `acquireCameraImage()`, never mix them with display-oriented values.
Select keyframes by baseline and rotation (5 cm or 10 degrees), drop frames when tracking is not `TRACKING` or
motion blur is high.

**D (data/format).** Adopt the formats the ecosystem already reads: the INRIA 3DGS PLY layout as the primary
export (every tool opens it), antimatter15 `.splat` for small web-viewer files, Niantic SPZ for compact files, and
watch Khronos `KHR_gaussian_splatting` for glTF. Keep the scene as struct-of-arrays in direct `ByteBuffer`s so a
file can be read once and handed to the GPU without copies. The capture should be stored as a Nerfstudio
`transforms.json` dataset so anyone can train it on a desktop with nerfstudio or gsplat. Every persisted packet
carries a format version.

**A (Android).** Pure Kotlin for everything that is maths or bytes, in JVM modules with no Android imports, so
the unit tests run fast in the gate. Android modules only for camera, GL, storage and screens. Stages talk through
small immutable packets over `Flow`; long work runs in a foreground service bound to a coroutine scope, cancellable,
watching thermal status. Memory: a 1M-splat cloud at SH3 is 236 MB of floats, beyond a normal app heap; cap what we
keep.

**X (sceptic).** Tonight, none of this exists: no ARCore dependency, no GL splat renderer, no format I/O, a
point-cloud data model, poker code everywhere. A differentiable rasteriser with a backward pass in Kotlin or GLSL is
days of work and easy to get subtly wrong. A server option needs the INTERNET permission, which this app has
promised not to have. ARCore's runtime is proprietary and Play-distributed, so it cannot be in an F-Droid build.
What will work first: capture with ARCore, seed Gaussians from ARCore's points and depth, show and export *that*
as a real 3DGS file. Optimisation comes after there is a pipeline to optimise.

## Round 2: cross-examination

- **X to R:** "A server means `android.permission.INTERNET`; the README and manifest promise none." **R:** concedes
  the server for v1, but wants the dataset exportable. **D:** the `transforms.json` capture bundle is exactly that:
  export it, train on any desktop, import the PLY back. No network code in the app. **Agreed.**
- **X to R:** "Is on-device training feasible at all?" **R:** at reduced scale yes: 30-60 keyframes at 320x240,
  up to ~100k Gaussians, SH degree 0, 2-5k iterations, L1 loss, simplified densification. A CPU tile rasteriser in
  Kotlin over 8 cores does a forward+backward of 50k splats at 320x240 in tens of milliseconds, so minutes per scan.
  **G:** a GLES 3.1 compute port is the speed-up later; the CPU version is the reference that the port is tested
  against. **Agreed:** CPU first, GPU later, same maths.
- **D to A:** "FloatArrays mean a copy into the GPU buffer." **A:** one copy at the render boundary is cheap next
  to the sort, and FloatArray SoA keeps the code, the tests and the JIT simple; direct buffers also hide memory from
  heap accounting, which makes OOMs harder to see. **Decided FloatArray SoA**; dissent recorded (D).
- **S to D:** "Which frame is a PLY in?" **D:** the format does not say. By convention files out of COLMAP-based
  trainers are in an OpenCV-like frame (x right, y down, z forward: RDF), and Niantic's spz library documents PLY as
  RDF and SPZ as RUB. **S:** then convert at the file boundary and only there; inside the app everything is ARCore
  world (RUB, Y up, metres). SH coefficients must be rotated too, which for this 180-degree flip is a sign per
  coefficient. **Agreed**, with a check against the real samples from #5 in SuperSplat (dissent: X doubts the
  convention holds for every file; import offers an "upright" toggle later).
- **G to R:** "SH degree 3 in the viewer?" **R:** imported files may carry it; trained ones won't on a phone.
  **G:** render degree 0 by default, evaluate up to 3 on the CPU per re-sort when the file has it (a later option).
  **Agreed:** store up to degree 3, train at 0, render 0 first.
- **A to G:** "Vulkan?" **G:** not for v1. Dissent recorded (G wants a Vulkan path once 1M+ splats matter).
- **S to X:** "F-Droid then gets no capture?" **X:** an F-Droid flavour without ARCore can view and import; capture
  needs a free tracker, which does not exist off the shelf under an MIT-compatible licence (OpenVINS and ORB-SLAM3
  are GPL-3). **Agreed:** ARCore behind a `PoseSource` seam; a `foss` flavour later.
- **X to S:** "ARCore poses drift; 3DGS blurs with bad poses." **S:** ARCore's VIO is good over a 1-2 minute
  object scan; loop closure is not available but drift over that span is centimetres. **R** wants joint pose
  refinement later. Recorded as a later option, not v1.

## Consensus (what the ADRs say)

1. Modules: pure-JVM `splat-core` (math, frames, types, packets, kernels), `splat-io` (formats), `splat-train`
   (initialisation and the CPU trainer); Android modules wrap them. ([ADR 0001](adr/0001-module-layout.md))
2. Types: `SplatCloud` as struct-of-arrays of raw (pre-activation) parameters in the canonical world frame;
   immutable value types for intrinsics, pose, keyframe; versioned packets with units in names or KDoc.
   ([ADR 0002](adr/0002-core-types.md))
3. Frames: canonical world = ARCore world (right-handed, +Y up, metres). Quaternions Hamilton, stored w,x,y,z
   inside the app. All conversions in one file, `FrameConversions.kt`. ([ADR 0003](adr/0003-coordinate-frames.md))
4. Tracking: ARCore (`getPose` + `getImageIntrinsics` + CPU image), behind a `PoseSource` seam; no ARCore in a
   future F-Droid flavour. ([ADR 0004](adr/0004-tracking.md))
5. Training: on-device only in v1, no network. First the seed-only splat (a real 3DGS cloud from ARCore points and
   depth), then a CPU optimiser; GPU compute later; capture bundle export for desktop training.
   ([ADR 0005](adr/0005-training.md))
6. Rendering: GLES 3.0 sorted instanced quads, data in textures, CPU 16-bit radix sort off the GL thread.
   ([ADR 0006](adr/0006-rendering.md))
7. Formats: 3DGS PLY (must, import and export), `.splat` (import and export), SPZ (import and export, after PLY),
   glTF `KHR_gaussian_splatting` later; capture bundle as Nerfstudio `transforms.json`. ([ADR 0007](adr/0007-formats.md))
8. Libraries: write the codecs and maths ourselves against the specs; adopt ARCore (optional), kotlinx.serialization,
   `java.util.zip`; consult gsplat (Apache-2.0), spz (MIT), antimatter15/splat (MIT) as references; never copy INRIA
   code (non-commercial licence). ([ADR 0008](adr/0008-libraries.md))
9. Tests: every conversion, codec, packet and kernel is unit tested on the JVM with fixed fixtures and stated
   tolerances; the CPU rasteriser is the golden renderer. ([ADR 0009](adr/0009-testing.md))

## Dissents kept

- **D:** wants direct `ByteBuffer` storage for `SplatCloud` (zero-copy, off-heap for 1M+ splats). Revisit if a
  real import OOMs; the packing step is the only place that would change.
- **G:** wants a Vulkan renderer with GPU radix sort once scenes above 1M splats matter.
- **R:** wants a self-hosted GPU training option and joint pose refinement; both need a decision from Hunter (the
  first needs the INTERNET permission, perhaps in a separate flavour).
- **X:** doubts that every PLY in the wild is RDF; wants an "upright" toggle on import and a manual check of each
  bundled sample.
