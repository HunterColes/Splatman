# ADR 0008: libraries and specs: adopt or write

Status: accepted (2026-11-01, council #2)

Splatman is MIT, built for F-Droid, with no INTERNET permission. A dependency must be MIT/Apache/BSD-compatible
and must not require Google Play Services unless it is optional and confined to the `full` flavour.

| Need | Choice | Licence | Status |
|---|---|---|---|
| Tracking | ARCore SDK `com.google.ar:core` | Google APIs ToS; runtime proprietary | **adopt**, optional at install, `full` flavour only once flavours exist (ADR 0004) |
| JSON (bundle) | kotlinx.serialization (already in the build) | Apache-2.0 | adopt |
| gzip (SPZ) | `java.util.zip` | JDK | adopt |
| Image encode/decode | Android `Bitmap`/`YuvImage` in Android modules; codecs take bytes | platform | adopt |
| Linear algebra, quaternions | our own `splat-core/math` (~300 lines) | MIT | **write**: small, and every convention must be ours |
| PLY / `.splat` / SPZ codecs | our own `splat-io` | MIT | **write** from the specs |
| Rasteriser forward/backward | our own `splat-train/raster` | MIT | **write**; maths cross-checked against gsplat's docs and code |
| GL renderer | our own `viewer-feature/rendering` | MIT | **write** |
| Calibration (later) | OpenCV for Android | Apache-2.0 | see #3; not in v1 |

## References (read, port with attribution where allowed, never vendor)

- gsplat (nerfstudio-project, Apache-2.0): projection, backward pass, densification; porting snippets is allowed
  with a NOTICE line.
- Niantic spz (MIT): SPZ layout and quantisation.
- antimatter15/splat (MIT): `.splat` layout, 16-bit counting sort, WebGL 2 shaders.
- INRIA gaussian-splatting: **non-commercial research licence**. Use the paper and the file layout (a format is
  not code); do not copy its code or CUDA kernels.
- Khronos `KHR_gaussian_splatting` draft: watched for the glTF export.

## Rejected

- PyTorch Mobile / ExecuTorch / ONNX Runtime training: heavy, no differentiable rasteriser op, and training graphs
  on mobile are immature.
- OpenVINS / ORB-SLAM3 for a free tracker: GPL-3 (needs Hunter's call).
- A Java math library (EJML, JOML): a dependency and a second set of conventions for ~300 lines of code; our own
  small set is easier to pin with tests.
