# ADR 0010: GPU training (GLES 3.1 compute, `splat-gpu`)

Status: accepted (epic #49). Supersedes nothing. It fills in point 5 of [ADR 0005](0005-training.md) ("a GLES 3.1
compute port of the same rasteriser, tested against the CPU one"). The children of #49 share it.

## Decision

1. **Module.** GPU code lives in a new Android library module `splat-gpu` (package
   `com.huntercoles.splatman.splat.gpu`, minSdk 26, depends on `splat-train`). It uses GLES 3.1 compute through
   `android.opengl.GLES31` on an offscreen EGL pbuffer context that one dedicated thread owns; every GL call runs on
   that thread. Shaders are Kotlin raw-string constants in `*Shaders.kt` files. The manifest does not require GLES 3.1
   (no `glEsVersion` 0x00030001): devices without it fall back to the CPU.
2. **CPU stays the reference.** The CPU path (`Rasterizer`, `Adam`, `SplatOptimizer`, `CpuTrainer`) stays the
   reference and the fallback. It is not changed except by the seam card, which keeps it bit-identical.
3. **Buffers.** SSBOs, std430, struct-of-arrays in the same layout as `SplatCloud`, `RenderBuffers` and `SplatGrads`,
   fp32. Keyframe targets are uploaded once as packed RGBA8 `uint`.
4. **Same order as the CPU.** One global sort of the visible splats by key (depth float bits, then splat index), then
   tile lists built in that order. On the GPU this is a stable LSD radix sort of (tile, depth bits) uint32 pairs over
   index-ordered input.
5. **No atomics for gradients.** GLES 3.1 has no float atomics, and int fixed-point atomics are not used. Backward
   writes per tile-entry partials (9 floats: du, dv, dconic a b c, dcolor r g b, dopacity, as in
   `SplatGrads.entryGrads`), then each splat gathers its entries over its tile rect in tile order (binary search into
   each sorted tile list). Results are deterministic.
6. **On-device parity tests** are instrumented tests in `splat-gpu/src/androidTest` (JUnit 4 with
   `androidx.test.ext:junit` from the catalog, no new dependency). They skip with `assumeTrue` when GLES 3.1 is
   missing and compare against the CPU code on seeded scenes (`Random(42)`; the 50-splat 64x64 scene and a
   2000-splat 320x240 scene). QA runs them on the phone with `./gradlew :splat-gpu:connectedDebugAndroidTest` and
   posts the result lines. The pure logic (layouts, dispatch sizes, key encoding, schedule, selection) also has JVM
   unit tests in `splat-gpu/src/test`, which the gate runs.
7. **Tolerances** (fix the code, never loosen them):

Projection means2d 1e-3 px, depths 1e-5 relative, conics 1e-4 relative, radii equal within 1 px; tile lists identical on >= 99.9% of entries. Image colour |gpu - cpu| <= 1e-3 on >= 99.9% of pixel channels, <= 2e-2 everywhere, mean <= 1e-4; finalT the same; contributors equal on >= 99.9% of pixels. Gradients per parameter group: relative L2 error ||g_gpu - g_cpu|| / ||g_cpu|| <= 1e-3, and viewGradNorms the same. One Adam step 1e-6 relative per element. One L1 loss 1e-5 relative. A full GpuTrainer run within 0.5 dB PSNR of CpuTrainer on the synthetic scene.

## Why

A compute port moves the rasteriser, the hot loop, off the CPU while the CPU code stays the golden reference, so
every GPU stage can be checked against it. Matching the CPU's sort and tile order makes results comparable and
deterministic. Gathering per-entry partials avoids atomics GLES 3.1 does not have. Not requiring GLES 3.1 in the
manifest keeps the app installable everywhere.

## Rejected

- **Float atomics, and int fixed-point atomics, for gradients:** float atomics are missing from GLES 3.1; fixed-point
  int atomics lose precision and make the sums order dependent.
- **Vulkan compute:** more code, and the viewer is GLES.
- **A CPU sort between GPU passes:** a read-back every iteration.
- **GL code in `splat-train`:** ADR 0001 keeps it pure JVM; the GL code goes in `splat-gpu`.
