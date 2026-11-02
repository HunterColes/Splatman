# Does Splatman need its own camera calibration?

Scout report, ticket #3, 2026-11-01. **V = verified (URL), E = estimate or opinion.**

**Answer.** No, not for v1. Read the intrinsics ARCore already reports for every frame. Treat the camera as a pinhole.
Refine focal length (and one radial term) inside the trainer later. A checkerboard screen is a last resort.

## Findings

1. **ARCore gives metric scale, so option (d) is moot as stated.** V: `Pose` translation units are meters
   ([Pose](https://developers.google.com/ar/reference/java/com/google/ar/core/Pose)). ARCore re-bases its world over time,
   so store poses per frame, not as a fixed world. Scale drift over a long scan is possible. E: no figure found.
2. **ARCore reports intrinsics, without distortion.** V: `Camera.getImageIntrinsics()` (CPU image) and
   `getTextureIntrinsics()` (GPU texture) return `fx, fy` and `cx, cy` in pixels plus the image size. They are unrotated
   and can change on each `Session.update()`
   ([CameraIntrinsics](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics),
   [Camera](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera)). The reference has no distortion
   field and no accuracy claim. E: ARCore's tracking uses its own internal model of the camera, so poses and these
   intrinsics belong together. A separate calibration would describe the lens, not ARCore's model.
3. **Camera2 is only a fallback.** V: `LENS_INTRINSIC_CALIBRATION` (`[fx, fy, cx, cy, s]`, API 23) and `LENS_DISTORTION`
   (Brown-Conrady, `k1..k3, p1, p2`, API 28) are both "Optional: may be null on some devices". The first is in
   pre-correction sensor pixels, so it must be rescaled to the output size
   ([CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)).
   V (forum, weak): some phones advertise the fields and return zeros
   ([thread](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/73712728/android-camera2-api-intrinsics-and-the-depth-output-capability)).
   I found no survey of how many devices fill them.
4. **Phone-model lookup tables do not work.** V: in a 2025 peer-reviewed study of iPhone 14 and Pixel 7 devices, focal
   length differed by more than 50 px and principal point by more than 35 px between calibration setups, and the authors
   conclude calibrate "individual phones and not individual smartphone models"
   ([ISPRS 2025](https://doi.org/10.5194/isprs-archives-XLVIII-G-2025-1669-2025)). The same paper shows that even
   a careful grid calibration (Kalibr) is not very repeatable on phones, so option (c) is not a clean win either.
5. **What intrinsics error costs 3DGS.** V: original 3DGS accepts only `PINHOLE` / `SIMPLE_PINHOLE` cameras, so distortion
   must be removed before training
   ([repo](https://github.com/graphdeco-inria/gaussian-splatting)). V: TrackGS fixes the FoV at 60° instead of learning it
   and loses 5.5 dB mean PSNR on four synthetic scenes (2.0 to 7.2 dB per scene), with 0.015° mean FoV error when learned
   against 0.271° for COLMAP ([arXiv 2502.19800](https://arxiv.org/html/2502.19800v3), Tables 3 and 5). That is a large
   error, not a few percent. The paper has no small-perturbation test, so the loss for a 1 to 3 % focal error is unmeasured.
   E: it is a mild blur and floaters at the edges, since the optimizer absorbs part of it by moving Gaussians.
6. **Training can refine intrinsics, but the common tools do not do it.** V: TrackGS derives the focal-length gradient and
   optimizes it jointly. It assumes no distortion and tests large-motion sequences only. V: gsplat documents `rasterization`
   as "not differentiable w.r.t. the camera intrinsics Ks"
   ([docs](https://docs.gsplat.studio/main/apis/rasterization.html)). V: Nerfstudio's camera optimizer (`off`, `SO3xR3`,
   `SE3`) only adjusts pose ([source](https://docs.nerf.studio/_modules/nerfstudio/cameras/camera_optimizers.html)). V:
   COLMAP can refine intrinsics in bundle adjustment ([cameras](https://colmap.github.io/cameras.html)). Splatman will
   write its own on-device trainer, so adding `fx` as a learnable scalar is our work, not a library switch.
7. **F-Droid.** V: ARCore needs "Google Play Services for AR" installed
   ([enable ARCore](https://developers.google.com/ar/develop/java/enable-arcore)). V: F-Droid's `NonFreeDep` flag is for
   apps that "require things that are not Free Software in order to run"
   ([Anti-Features](https://f-droid.org/docs/Anti-Features/)). E: an ARCore-based Splatman will carry `NonFreeDep`.
   That is the architecture ticket's call, but it means the intrinsics path must be one interface with more than one
   source. OpenCV is Apache-2.0 (E: from memory, check before adding it). It would add tens of MB of native code for
   a screen few users need.

## Options

| Option | Verdict |
|---|---|
| (a) Platform intrinsics | **Yes, v1.** ARCore per frame is the primary source. Camera2 is the source only on a future non-ARCore path, and a cross-check otherwise. |
| (b) Typed phone model, nominal lookup | **No.** Per-unit variation is larger than any table's resolution (finding 4), and we would have to ship and maintain the table. |
| (c) On-phone OpenCV board | **Only when (a) fails or later fine-tuning is shown to need it.** Cost: UX, native size, repeatability (finding 4). Never for ARCore capture, since it would mismatch ARCore's own model. |
| (d) Ignore | **No for intrinsics, yes for scale.** Scale comes free from ARCore, but a wrong pinhole model costs real PSNR (finding 5). |

## Recommendation

**V1:** build `CameraIntrinsics(fx, fy, cx, cy, width, height, source, distortion?)` in pixels, filled from
`getImageIntrinsics()` per keyframe, and train with a pinhole model. Do not read distortion in v1. Log the Camera2 numbers
beside them as a sanity check (a mismatch over 5 % after rescaling is a bug to look at, not a thing to fix at runtime).
Reject frames when ARCore is not tracking. **Later:** have the trainer learn one shared `fx` scale plus `k1` (a small
learning rate and a warm-up with it frozen, as TrackGS does), and use the learned value for export. Only if that fails on
real scans, add a calibration screen (Charuco, 15 to 20 views, shows reprojection RMS), used for non-ARCore sources.
