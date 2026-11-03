# ADR 0006: rendering approach and budget

Status: accepted (2026-11-01, council #2)

## Decision

**GLES 3.0, sorted instanced quads**, the approach of antimatter15/splat, gsplat.js and PlayCanvas. The manifest
declares `<uses-feature android:glEsVersion="0x00030000" android:required="true"/>`.

- **Packing** (pure Kotlin, `viewer-feature/rendering/pack`, unit tested): once per loaded cloud, activate and pack
  each splat into two texels of an `RGBA32UI` texture (width 2048): centre as 3 x f32, the 3D covariance
  `Sigma = R S S^T R^T` as 6 x f16, colour RGBA8 (`0.5 + C0 * shDc`, clamped, alpha = sigmoid(opacityLogit)). 32 bytes
  per splat, 32 MB for 1M.
- **Sort** (pure Kotlin, `splat-core/sort`): view-space depth of each centre, 16-bit keys between that frame's min
  and max, one counting/radix pass, back to front. Runs on a worker coroutine; the GL thread draws with the last
  finished order and uploads the new index buffer (`u32` per splat) when it arrives. Re-sort only when the view
  direction changed by > 0.5 degrees or the position moved; never block a frame on a sort.
- **Vertex shader**: per instance, fetch the splat, project its covariance to 2D with the EWA Jacobian (ADR 0003,
  Ccv), add 0.3 px^2 to the diagonal, take eigenvalues, and emit a quad of +-3 sigma along the eigenvectors (cap the
  radius at 1024 px; cull behind the camera and outside 1.2x the frustum).
- **Fragment shader**: `alpha = opacity * exp(-0.5 d^T Sigma2D^-1 d)`, discard below 1/255, output premultiplied;
  blend `ONE, ONE_MINUS_SRC_ALPHA`, depth test off.
- **SH**: degree 0 in the shader for v1. Higher degrees in imported files are kept in the cloud; evaluating them per
  re-sort on the CPU is a later option.
- **Render scale**: draw into an FBO at 0.5-1.0 of screen resolution, chosen by frame time (drop when the last 30
  frames average > 20 ms, rise when < 12 ms).
- The existing `GaussianCameraController` (orbit, pan, zoom) stays and is rebased on `splat-core/math`.
- **Amendment (2026-11-03, #262): no instancing.** SwiftShader (the emulator's GL) runs every instance as a draw of
  its own, so 450k splats took about 10 s a frame at any resolution. The order is now an `R32UI` texture (width
  2048) instead of a `u32` vertex buffer, and the quads are drawn 16384 splats per `glDrawElements` from one static
  `u16` index buffer (two triangles per quad), `u_base` the chunk's first slot; the vertex shader takes its corner
  and slot from `gl_VertexID`. Same vertices per splat, same memory (4 B/splat order), a few draws per frame.

## Budget (2022 mid-range phone, e.g. Adreno 642L / Mali-G68, 1080p screen)

| Item | Target |
|---|---|
| Frame | 60 fps at <= 300k splats, >= 30 fps at 1M |
| GPU draw | <= 10 ms at 300k |
| Sort | <= 15 ms for 1M on one worker core, off the GL thread |
| Index upload | <= 2 ms (4 MB at 1M) |
| GPU memory | 32 B/splat packed + 4 B/splat index |
| Load | 1M-splat PLY parsed and packed in < 3 s |

The QA card measures these on Hunter's phone; a miss becomes a perf ticket, not a silent cap.

## Rejected

- Vulkan + GPU radix sort: faster above ~2M splats, but a second graphics stack (council dissent G).
- Compute-shader tile rasteriser for display: needs GLES 3.1 and is the training port's job first.
- Rendering splats as points (the current renderer): not Gaussian splatting.
