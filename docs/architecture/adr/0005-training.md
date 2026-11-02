# ADR 0005: where training runs and what v1 does

Status: accepted (2026-11-01, council #2)

## Decision

1. **On the device only, no network.** The app keeps no INTERNET permission. A self-hosted server option is
   rejected for v1 (it needs the permission; council dissent R). Instead the capture bundle (ADR 0007) is exportable,
   so a user can train it on a desktop with Nerfstudio or gsplat and import the PLY back.
2. **Stage 1, the seed splat (must ship first).** `splat-train/init` turns `SeedPoints` into a real `SplatCloud`,
   the 3DGS initialisation: one Gaussian per seed point, isotropic log-scale = ln(mean distance to its 3 nearest
   neighbours) (clamped to [1 mm, 10 cm]), identity rotation, opacity logit(0.1), `shDc = (rgb - 0.5) / C0`,
   SH degree 0. This is viewable and exportable on its own and is the fallback if optimisation fails or is
   cancelled.
3. **Stage 2, the CPU optimiser.** `splat-train` in plain Kotlin, multi-threaded over tiles:
   - images: keyframes downscaled to long side 320 px (configurable), intrinsics scaled with them;
   - differentiable tile rasteriser (16x16 tiles), forward and backward for means, log-scales, rotations, opacity
     logits and SH (degree 0 in v1), maths as in the 3DGS paper / gsplat (EWA projection, 0.3 px low-pass,
     3-sigma extent, alpha cap 0.99, stop at transmittance 1e-4);
   - loss: L1 in v1 (0.8 L1 + 0.2 D-SSIM later);
   - Adam per parameter group with the 3DGS learning rates (positions 1.6e-4 x scene extent decaying to 1.6e-6,
     SH dc 2.5e-3, opacity 0.05, log-scale 5e-3, rotation 1e-3);
   - densification every 100 iterations from 300 to 60% of the run: clone/split above view-space gradient 2e-4,
     prune opacity < 0.005 and oversize; hard cap `maxSplats` (default 100k);
   - default 3000 iterations; snapshots every 250 for the live preview.
   - Target: a 40-keyframe object scan in under 5 minutes on a 2022 mid-range phone. Measured by the QA card;
     if it misses, lower the defaults rather than ship slow.
4. **The runner** (`capture-feature/processing`): a foreground service with a `Flow<TrainingProgress>`, cancellable,
   that reads `PowerManager.getCurrentThermalStatus()` (API 29+) and pauses at `THERMAL_STATUS_SEVERE`, then writes
   the result PLY to the scan's folder. It always runs stage 1 first, then stage 2 if enabled.
5. **Later:** a GLES 3.1 compute port of the same rasteriser (tested against the CPU one), SH degree 1, D-SSIM,
   pose refinement.

## Interface

```kotlin
interface Trainer {
    fun train(input: TrainingInput): Flow<TrainingProgress> // last emission carries the final cloud
}
class SeedTrainer : Trainer        // stage 1 only
class CpuTrainer : Trainer         // stage 1 then stage 2
```

## Why

A pipeline that works end to end with stage 1 gives Hunter a phone build that captures, shows and exports a real
3DGS file; stage 2 improves quality without changing any interface. The CPU trainer is also the reference for the
GPU port and for the renderer's golden images.
