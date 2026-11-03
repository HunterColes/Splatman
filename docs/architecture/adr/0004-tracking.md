# ADR 0004: tracking with ARCore, behind a seam

Status: accepted (2026-11-01, council #2)

## Decision

- v1 tracks with **ARCore** (`com.google.ar:core`): 6-DoF metric, gravity-aligned poses, CPU-image intrinsics, the
  feature point cloud and, where the phone supports it, the Depth API (`acquireDepthImage16Bits`, millimetres).
  No SfM (COLMAP or similar) runs on the phone.
- ARCore sits behind an interface in `capture-feature`:
  `interface PoseSource { val frames: Flow<TrackedFrame>; suspend fun start(); fun stop() }`. Nothing outside the
  ARCore implementation imports `com.google.ar.*`.
- Per frame the source reads `Camera.getPose()`, `Camera.getImageIntrinsics()`, `Camera.getTrackingState()`,
  `Frame.getTimestamp()` and the CPU image (YUV_420_888, default 640x480) and converts through `FrameConversions`
  (ADR 0003).
- Keyframe selection (pure Kotlin, `splat-core/capture`): accept a frame when tracking is `TRACKING`, and it moved at
  least 5 cm or turned at least 10 degrees from the last accepted keyframe, and its sharpness (variance
  of the Laplacian on the luma plane, downsampled) is not in the bottom quartile of the last 30 frames. Cap at 120
  keyframes; thresholds live in one `KeyframePolicy` data class.
- Seed points: ARCore feature points (confidence >= 0.3) plus, when depth is available, depth pixels unprojected
  with the depth image's intrinsics, merged in a 1 cm voxel grid (first colour wins, sampled from the keyframe),
  capped at 200k points.
- ARCore is in the v1 build but **optional at install** (manifest `<meta-data android:name="com.google.ar.core"
  android:value="optional"/>`): phones without it still view, import and export; capture shows "this phone does
  not support ARCore" and, where ARCore is installable, offers the install.

## F-Droid

ARCore's runtime (Google Play Services for AR) is proprietary and installed from the Play Store, and the client
library is a binary under Google's terms, so an F-Droid build cannot contain it. Plan: product flavours `full`
(with ARCore) and `foss` (no ARCore module, capture hidden, viewer/import/export intact), a later card. A free
tracker for `foss` is out of scope: the off-the-shelf VIO systems (OpenVINS, ORB-SLAM3) are GPL-3, which would
relicense the `foss` APK; that needs Hunter's decision.
foss recovers poses after capture instead, with SfM: see [ADR 0012](0012-foss-pose-source.md).

## Rejected

- CameraX + our own VIO: months of work.
- `getDisplayOrientedPose()` with screen-rotated images: the intrinsics would then have to be rotated too; one
  sensor-oriented set is simpler and is what ARCore's own CPU-image docs pair.
- Pose refinement during training: later option (council dissent R), not v1.
