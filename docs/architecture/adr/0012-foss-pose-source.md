# ADR 0012: foss pose source, post-capture structure from motion

Status: proposed (spike #200). The Pixel timing card fills in the Numbers table and sets this to accepted.

## Context

The `foss` flavour ships without Google Play, so it has no ARCore ([ADR 0004](0004-tracking.md)) and no live
poses. The research of 2 Nov picked route A: record the frames and IMU during capture, then recover the camera
poses after capture with structure from motion (SfM) on the phone, and hand the result to Processing in the same
bundle layout ARCore scans use. The off-the-shelf live VIO systems are GPL-3 and stay the fallback, not the plan.

## Decision

1. **Native module `splat-sfm`** (Android library, NDK + CMake, C++17, ABIs `arm64-v8a` and `x86_64` only) runs
   OpenCV only: features, matching, geometric verification, two-view initialisation, PnP registration and
   triangulation.
2. **Everything else is pure Kotlin/JVM** in `splat-core`, package `com.huntercoles.splatman.splat.sfm`, tested in
   the gate: the types, gyro priors, bundle adjustment, gravity and scale alignment.
3. **One native dependency:** OpenCV from Maven Central (`org.opencv:opencv`, newest 4.x, Apache-2.0), consumed
   through prefab ([ADR 0008](0008-libraries.md)).
4. **Poses out** are `CameraPose` (`T_W_Cgl`) in world frame W of [ADR 0003](0003-coordinate-frames.md): +Y up from
   the mean accelerometer gravity, origin at the median sparse point, scale set so the median camera-to-origin
   distance is 1.0 (unit-free, close to ARCore metres at arm's length). OpenCV's world-to-camera `(R, t)` is
   converted only inside `FrameConversions` (ADR 0003).
5. **Output on disk:** `transforms.json` (registered frames only) and `seed.ply` in the scan bundle, in the
   [ADR 0007](0007-formats.md) layout, so Processing and the trainer read it unchanged.
6. **Flavours:** the app takes `splat-sfm` as `fossImplementation` only; `full` keeps ARCore and is unchanged.

## Types

Card "SfM types" defines them in `com.huntercoles.splatman.splat.sfm`: `SfmFrame`, `ImuSamples`, `SfmInput`,
`SfmOptions`, `FeatureKind`, `FramePair`, `SparseModel`, `SfmStage`, `SfmStats`, `SfmResult`, `PoseRecovery`.

## Rejected

Not in this epic:

- Ceres: would be built from source for two ABIs on every gate run.
- OpenMVG, COLMAP: heavy dependency trees.
- ALIKED + LightGlue on ONNX Runtime: needs a model export Hunter has to run.

Ruled out (licences): DUSt3R, MASt3R, CUT3R, Splatt3R, SuperPoint/SuperGlue, VGGT.

## Fallback

#201: OpenVINS live VIO (GPL-3), taken if the Pixel run registers under 80 % of 150 hand-held frames or takes
over 5 minutes wall time.

## Open question: OpenCV for F-Droid

F-Droid builds from source and rejects prebuilt `.so` files. The OpenCV prebuilt from Maven is fine for the spike,
but a release needs OpenCV built from source. Not solved here.

## Numbers

Filled in by the Pixel timing card.

| Stage | Wall ms | Registered / total | Mean reprojection px | Median reprojection px | APK size added |
|---|---|---|---|---|---|
| | | | | | |
