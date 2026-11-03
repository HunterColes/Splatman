# ADR 0016: our own visual-inertial odometry, staged

Status: accepted (2026-11-03, epic #336). Supersedes in part ADR 0012 point 4 (unit scale, when the visual-inertial pass
converges) and adds to ADR 0007 (the tracking stream and per-frame timing keys). Builds on ADR 0015 (no ARCore;
`PoseSource` and `TrackedFrame` stay as the seam).

## Context

Splatman is MIT and has no tracker since ADR 0015. Every mature open VIO (OpenVINS, open_vins_mobile, VINS-Mono/Mobile,
HybVIO, ORB-SLAM3) is GPL-3; the permissive ones fall short (Granite: no mono-inertial; Basalt: stereo; Kimera: no
Android, ~50k lines; msckf_vio, XIVO: not open for us). XRSLAM (Apache-2.0, mono-inertial, iPhone AR app) is the one
permissive candidate. Feasibility study: hunter/Bombadil `research/splatman-own-vio-2026-11-03.md` (round 26). A 10-pose
OC-MSCKF ran at 15 Hz on a Galaxy S4 with 0.6 to 0.9 % drift (Guo et al., RSS 2014); the nearest ARM timing is HybVIO's
33 ms/frame on a Raspberry Pi 4.

What a pose is for: capture guidance (coverage, distance, move slower) and pose priors for SfM + BA after capture (ADR
0012), which stays the accuracy pass. No relocalisation, planes, depth, anchors or multi-session maps, ever, under this
ADR.

On object orbits a live filter adds little over the gyro plus BA after capture; only rooms need a live 6-DoF pose
(translation coverage, "tracking lost, go back" while the user is there).

## Decision

1. **Two stages.** Stage 1 (6 to 10 person-weeks) ships on its own: visual-inertial BA after capture plus estimator-free
   live guidance. Stage 2 (16 to 30 person-weeks), a live MSCKF filling `PoseSource`, starts only through the start gate
   (point 9) and is stopped by the kill gate (point 10). Homebrew VIO usually dies at initialisation, fast turns,
   textureless walls, time offset and rolling shutter; every card names which of these it hardens.
2. **Stage 1 language and place: Kotlin, `splat-core`**, package `com.huntercoles.splatman.splat.sfm.vi`, pure JVM,
   tested in the gate. It runs after capture, so it has no frame budget; target under 30 s for 300 keyframes on the
   Pixel 8 Pro on top of SfM. It reuses the existing LM `BundleAdjuster`'s reprojection code; the visual-inertial solver
   is a new `ViBundleAdjuster`, so plain BA stays as the fallback.
3. **Stage 1 maths** (from the papers only):
   - IMU preintegration on the manifold with bias Jacobians and first-order bias update (Forster et al., T-RO 2017);
   - visual-inertial alignment for gyro bias, scale, gravity and velocities (Qin et al., VINS-Mono, T-RO 2018, section
     V), then gravity refined on S2;
   - one global time offset `t_imu = t_cam + td`, modelled as a fixed delay plus half the per-frame exposure (Qin and
     Shen, IROS 2018);
   - first-order rolling shutter: a row at `v` is exposed at `t + (v - h/2) * readout / h`, with the pose moved by the
     frame's velocity and angular rate;
   - states per keyframe: pose, velocity, gyro and accel bias; global: gravity direction, scale (until fixed),
     camera-IMU rotation, lever arm, `td`, `readout`, accel scale per axis.
4. **Calibration.** `LENS_POSE_ROTATION` is the rotation from the Android sensor axes to the camera axes (Android
   reference; `PRIMARY_CAMERA` only sets the translation origin). The Pixel's (x,y,z,w) = (-0.7071, 0.7071, 0, 0) is the
   nominal mount and the prior, refined by a hand-eye fit of gyro-integrated against SfM relative rotations. Lever arm
   (prior 0 +- 5 cm), `td` (prior 0 +- 30 ms), `readout` (prior from `SENSOR_ROLLING_SHUTTER_SKEW` when reported, else 0
   +- 33 ms) and accel bias/scale are estimated. On object orbits the lever arm is frozen at its prior when its
   covariance does not shrink.
5. **Frames.** New frame **S**: Android sensor axes of the device (`imu.csv`'s axes), right-handed, m, rad/s. The
   estimator works in a gravity-aligned world G (+Z up, against gravity) with the IMU body S; conversion to W (ADR 0003:
   +Y up) happens only in `FrameConversions`, in one named function. Quaternions stay Hamilton w,x,y,z.
6. **Scale and output.** When the visual-inertial pass converges, `transforms.json` and `seed.ply` are metric (metres)
   and gravity-aligned; ADR 0012 point 4's unit scale stays for the fallback. Fallback (the stage 1 kill rule per scan):
   if the VI pass does not converge, or its scale disagrees with the accelerometer-only alignment by over 5 %, drop the
   IMU terms and keep plain SfM. `SfmStats` reports which happened and the estimated `td`, `readout`, lever arm and
   camera-IMU rotation.
7. **Data path.**
   - IMU at 200 Hz (`SAMPLING_PERIOD_US = 5000`, the `registerListener` cap on API 31+ with no permission); never
     `HIGH_SAMPLING_RATE_SENSORS`.
   - Per keyframe in `capture.json`, when reported: `exposure_ns` (exists), `rolling_shutter_skew_ns`,
     `focus_distance_dpt`. In `calibration.json`: `focus_distance_calibration` (Camera2
     `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` name). `splatman_capture_version` stays 1: all new keys are optional and
     unknown keys are ignored on read.
   - **Tracking stream** (developer setting, off by default): the Y plane of the existing YUV capture stream,
     box-downsampled by 3 (1920x1080 -> 640x360, same field of view, intrinsics divided by 3, same timestamps as the
     keyframes), every frame, written as `stream.y8` (raw 8-bit frames back to back, row-major, no padding) and
     `stream.csv` (header `index,timestamp_ns,exposure_ns,rolling_shutter_skew_ns,focus_distance_dpt`, empty cell when
     not reported); `capture.json` gets an optional `"stream": {"file_path": "stream.y8", "index_path": "stream.csv",
     "w": 640, "h": 360, "downsample": 3}`. No second Camera2 stream.
8. **Testing.** Every Jacobian has a finite-difference test (central differences, relative error under 1e-4 at double
   precision). A synthetic visual-inertial simulator in `splat-core` tests (spline trajectory, IMU with noise and bias,
   points projected with rolling shutter and time offset) drives the gate tests. Real data runs outside the gate: TUM-VI
   room1 to room6 (CC BY 4.0, converted to our capture layout, kept outside the repo) through `tools/vio/`, and 20 Pixel
   scans (10 objects, 10 rooms) with a printed marker board for scale. ATE is our own Kotlin scorer (Umeyama alignment,
   RMSE); `evo` (GPL-3) is used only once, outside the repo, to cross-check it. EuRoC and ADVIO (non-commercial) are
   local only, never in the repo or CI.
9. **Stage 1 gates.** TUM-VI room1 to room6 mono, ATE <= 0.03 m after alignment. Pixel: scale error <= 2 % against the
   board and convergence on >= 18 of 20 scans. Kill: scale error over 5 % or failure on over 20 % of scans: drop the IMU
   terms, keep plain SfM. **Stage 2 start gate:** at least 3 of the 10 room scans needed a rescan that a live pose would
   have caught on site.
10. **Stage 2 shape** (re-split by an architect after the start gate and the week-1 spikes): MSCKF (Mourikis and
    Roumeliotis, ICRA 2007) with FEJ (Huang et al., IJRR 2010), 8 to 10 clones at 15 Hz, IMU propagation publishing at
    30 Hz; `td`, readout and lever arm as filter states (Li and Mourikis, IJRR 2013); FAST plus pyramidal KLT on the
    tracking stream with gyro-predicted search and 2-point RANSAC; static gyro-bias init then a "move sideways"
    alignment mapped to `TrackingState` and Capture's guidance. **Language: NDK C++17**, new module `splat-vio`, Eigen
    (MPL-2) and Sophus (MIT), OpenCV imgproc/video already in `splat-sfm` or our own KLT; the estimator core has no
    Android headers so it builds for the host and runs the TUM-VI bench there; a thin JNI shim and `VioPoseSource :
    PoseSource` in `capture-feature`. Front end on the CPU with NEON first, not `splat-gpu` compute. Kill gate 8 weeks
    after stage 2 starts: TUM-VI room ATE no worse than 2x unmodified HybVIO, p95 <= 15 ms/frame on the Pixel at 15 Hz
    with capture running, no divergence on >= 18 of 20 Pixel scans; fail: keep stage 1, live VIO off. Thermal: drop to
    10 Hz, then gyro-only guidance.
11. **Week 1 spikes** (no code into the repo): time open_vins_mobile (GPL-3) on the Pixel and score HybVIO offline, by
    one quarantined agent; build XRSLAM (Apache-2.0) for arm64 and time it. If XRSLAM meets the stage 2 kill-gate bars,
    stage 2 becomes "port XRSLAM, keep its notices, add `td`, readout and lever-arm states" and is re-costed.
12. **Clean room.** Agents working on estimator cards (stage 1 maths, stage 2) never open GPL source (OpenVINS,
    open_vins_mobile, VINS, HybVIO, ORB-SLAM3, evo); they write from the papers. GPL tools run only in the quarantined
    spike card, outside the repo, and only their numbers come back. Apache-2.0 XRSLAM may be read and ported with its
    notices kept.

## Consequences

- Stage 1 gives metric, gravity-aligned scans and better SfM on weak texture without a live tracker; live guidance stays
  gyro and autofocus driven.
- The capture layout grows optional keys and two optional files; old readers and old scans keep working.
- A stage 2 that fails its kill gate costs at most 8 weeks and leaves stage 1 intact.

## Rejected

- Vendoring OpenVINS, VINS-Mono or ORB-SLAM3: GPL-3 would relicense the app.
- Live MSCKF first: on object orbits it adds little over gyro plus BA after capture, and it is the larger risk.
- Stage 2 in Kotlin: no SIMD, GC pauses, and the 15 ms budget is already tight in C++ (33 ms on a Pi 4).
- A second Camera2 stream for tracking: the keyframe YUV stream already runs every frame; a downsampled Y plane gives
  the same timestamps and intrinsics for free.
- `evo` linked or vendored: GPL-3.
- Ceres or GTSAM for stage 1: native build per ABI; the Kotlin LM already exists.
