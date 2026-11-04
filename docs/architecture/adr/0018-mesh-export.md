# ADR 0018: mesh export (density grid, Surface Nets, SH0 vertex colour, .glb)

Status: accepted (2026-11-04, epic #444).

## Context

Many tools that take a 3D scan read triangle meshes, not Gaussian splats. Epic #444 adds a mesh export beside the
splat formats of ADR 0007. The mesh engine, the writer and the Export row are separate cards; this ADR is the one
design they build.

## Decision

1. **Method: opacity-weighted Gaussian density on a regular grid, iso-surface by naive Surface Nets (Gibson 1998),
   colour from SH degree 0.** The field is `D(x) = sum_i alpha_i * exp(-1/2 (x-mu_i)^T Sigma_i^-1 (x-mu_i))`, with
   `alpha = sigmoid(opacityLogit)` and `Sigma = R S^2 R^T` (splat-core projection/Covariance). Splats with
   alpha < 0.05 are skipped. Each splat adds to the grid points inside its 3-sigma box, clipped to 5 cells each side.
   Inside is `D >= 0.5`.
2. **Grid.** Bounds are the per-axis 0.5 % and 99.5 % quantiles of the kept centres (splats outside are dropped),
   padded by 2 + 5 cells. Cell size = longest side / (256 - 2*pad), so at most 256 points per axis (64 MiB of
   float32). The padding closes every surface.
3. **Surface Nets.** One vertex per cell whose 8 corners straddle the iso level, at the mean of its edge crossings
   (linear interpolation). One quad per crossed grid edge, as two triangles split on the shorter diagonal (ties on
   corner 0-2), wound counter-clockwise seen from outside. Normal per vertex = -grad D (central differences,
   trilinear at the vertex), unit length.
4. **Colour.** Per vertex, `sum w_i * clamp(0.5 + C0 * shDc_i, 0, 1) / sum w_i` with `w_i = alpha_i * G_i(vertex)`,
   over the splats whose clipped box holds the vertex's cell. Values are display (sRGB); mid grey 0.5 when no splat
   reaches. View-dependent SH is dropped.
5. **Caps.** At most 2,000,000 triangles. When a cheap crossing count over the grid says more, density is rebuilt at
   floor(0.8 x) points per axis; the attempt at or under 128 points per axis is kept as it is. A cloud with no surface
   (all faint, or empty) is the `MeshEmpty` error, not an empty file.
6. **Determinism.** The same cloud gives the same bytes. Work may be split over z-slabs on several threads, but every
   grid value and vertex colour sums its splats in splat-index order, so any thread count gives bit-identical output.
7. **Format: binary glTF 2.0 (`.glb`).** One TRIANGLES primitive: POSITION and NORMAL float32 VEC3, COLOR_0
   unsigned-short normalised VEC4 (sRGB to linear per IEC 61966-2-1, alpha 1), UNSIGNED_INT indices. One material
   (baseColorFactor 1, metallic 0, roughness 1, doubleSided) with `KHR_materials_unlit` in `extensionsUsed`, not
   `extensionsRequired` (the colours already hold the lighting). Frame W = glTF frame (ADR 0003): no conversion. The
   file is `<scan name> mesh.glb`, MIME `model/gltf-binary`, so it never collides with a future splat-glTF
   `<scan name>.glb` (#414).
8. **Budget (Pixel 8 Pro, 1M-splat scan).** At most 20 s from tap to written file (20 microseconds per splat on
   smaller scans), and at most 192 MiB of Java heap above the loaded cloud at peak. Peak plan: the 64 MiB density grid
   is dropped before the 64 MiB cell-to-vertex index is built for the colour pass; mesh arrays at the cap are about
   60 MiB.
9. **Progress and cancel.** One monotonic 0..1 progress: density 0 to 0.45, surface 0.45 to 0.55, colour 0.55 to
   0.95, writing 0.95 to 1. Cancel is checked every 4096 splats and every z-slice and throws
   `java.util.concurrent.CancellationException`.
10. **Libraries: none.** Own Kotlin in splat-core (`mesh/`) and splat-io (`gltf/`), MIT. Surface Nets is written from
    the paper's description (S. Gibson, "Constrained elastic surface nets", 1998); glTF 2.0 and KHR_materials_unlit
    from the Khronos specs (royalty-free). No GPL code, no new dependency.

## Rejected

- **OBJ:** vertex colours are a non-standard extension many tools drop; text is about 3x larger; needs `.mtl`.
- **PLY mesh:** few general tools read its colours.
- **Marching cubes:** the common 256-case table has no clear licence, about 2x the triangles, ambiguous cases.
- **Poisson reconstruction:** PoissonRecon is C++ via the NDK and needs oriented normals splats do not reliably give.
- **Open3D:** a large native dependency.
- **Training-time mesh methods** (SuGaR, 2DGS, GOF): they change training.
- **A UV-textured mesh** (unwrap and atlas): later, if vertex colour at 256 cells looks too soft.
- **Decimation:** later.
