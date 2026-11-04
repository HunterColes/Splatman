# glTF `KHR_gaussian_splatting`: ratified? what a `.glb` export needs (scout, card #412, 2026-11-04)

Tags: **[V]** verified in a primary source, **[E]** estimate, **[O]** opinion.

## Status

- **Base extension is ratified.** [V] The spec README says "Complete, Ratified by the Khronos Group" (no date line in it):
  https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_gaussian_splatting/README.md. The
  extensions index lists it under Ratified: https://github.com/KhronosGroup/glTF/blob/main/extensions/README.md.
- **Date: 3 Sep 2026.** [E] The registry commit "Update extensions registry (#2642)" is that day (folder history:
  https://github.com/KhronosGroup/glTF/commits/main/extensions/2.0/Khronos/KHR_gaussian_splatting); the "ratified on" claim comes from
  https://radiancefields.com/platforms/the-khronos-group, which says Khronos issued no announcement. The only Khronos
  press release is the release candidate of Feb 2026 (ratification then "expected Q2"):
  https://www.khronos.org/news/press/gltf-gaussian-splatting-press-release.
- **Still moving.** [V] "Rebase component types (#2677)" landed 13 Oct 2026, after ratification. Re-read the README when the writer starts; the table is its state on 4 Nov 2026.
- **Compression companion: not ratified.** `KHR_gaussian_splatting_compression_spz_2` is a draft nested extension
  (SPZ v2 blob, from Niantic): PlayCanvas calls it "still a draft" and does not support it
  (https://developer.playcanvas.com/user-manual/gaussian-splatting/formats/glb/); its folder is not on glTF `main`
  (404). [V] Look again when it appears in the Ratified list of the extensions index.

## What the file is

A normal glTF/GLB. One mesh primitive with `mode: 0` (POINTS); `extensions.KHR_gaussian_splatting` on the primitive
with `kernel: "ellipse"` and `colorSpace: "srgb_rec709_display"` (both required; 3DGS training is display-referred
sRGB [E]); `extensionsUsed` lists the name (`extensionsRequired` not needed: a viewer without it draws a point cloud).
The material is ignored by splat renderers. [V]

## Attributes against our PLY (`PlyReader.kt`) and `SplatCloud`

| glTF semantic (accessor) | PLY field | Conversion from `SplatCloud` (frame W) |
|---|---|---|
| `POSITION` VEC3 float32 (min/max required) | `x,y,z` | none: glTF = W (ADR 0003 rule 6) |
| `KHR_gaussian_splatting:ROTATION` VEC4 float32 | `rot_0..3` (w,x,y,z) | none to the frame; reorder to x,y,z,w with `FrameConversions.quatToXyzw` |
| `...:SCALE` VEC3 float32, linear, not negative | `scale_0..2` (log) | `exp(logScale)` |
| `...:OPACITY` SCALAR float32, 0..1 | `opacity` (logit) | `1 / (1 + exp(-logit))` |
| `...:SH_DEGREE_0_COEF_0` VEC3 float32 | `f_dc_0..2` | none: spec colour is `SH00 * 0.2820947917738781 + 0.5`, same as 3DGS [V] |
| `...:SH_DEGREE_d_COEF_k` VEC3 float32; d=1 k 0..2, d=2 k 0..4, d=3 k 0..6 | `f_rest_*` | regroup: PLY and `shRestIndex` are channel-major (`c*K + k`), glTF wants one RGB triple per coefficient |

[V] for the glTF side (README), the PLY side is read from `PlyReader`/`PlyWriter`. Degrees must be complete and in order
(no partial degree); a degree-3 file has 20 accessors. Float32 everywhere is simplest: ~236 B per splat at degree 3 (PLY 248 B). [V]

## Frames and signs against ADR 0003

- **No flip, no SH sign table.** [V] `SplatCloud` is in W and glTF is W, so positions, rotations and SH go out as stored.
  The `G = diag(1,-1,-1)` conversion and the per-coefficient signs of rule 5 belong to PLY/`.splat`/SPZ only; the glTF
  writer must not call `worldToFile`.
- **Quaternion order** is "the usual glTF order"; the spec text does not spell it out, base glTF is x,y,z,w. [E]
- **SH order and phase.** Degree 1 is m = -1, 0, 1 = y, z, x, the order we already use; the spec's real basis has the
  Condon-Shortley phase, which is what the reference 3DGS code bakes into its constants. [E] That implies a plain copy;
  prove it by loading the result in a reader, not by this paragraph.
- **Node transform.** One node, identity matrix. The spec asks viewers to rotate SH with the node transform, so identity
  keeps every viewer simple. [V]

## Who reads it (dated)

- CesiumJS 1.139 and Cesium for Unreal 2.23, March 2026 [V]
  (https://radiancefields.com/cesium-brings-khr_gaussian_splatting-support-to-cesiumjs-and-unreal-engine).
- PlayCanvas engine reads it; its SplatTransform writes it (`splat-transform scene.ply scene.glb`), ellipse kernel only [V]
  (page above, undated).
- Babylon.js: one aggregator says yes, not confirmed [E]. Known-implementations list also has JSplat and `3dgs_ply2gltf`.
- Not found: three.js, Blender, Unity (Cesium plans it, no date).

## Smallest writer in `splat-io`, no new dependency

`GltfWriter.write(cloud: SplatCloud, out: OutputStream)`, about 120 lines: a GLB is a 12-byte header, a JSON chunk
(space-padded to 4 bytes) and a BIN chunk (zero-padded). Build the JSON with a `StringBuilder`; no JSON library. BIN holds
each attribute as its own tightly packed little-endian float array (one `bufferView` + accessor each, no stride), so it can
stream one attribute at a time. Only `POSITION` needs min/max, computed in one pass. A reader is not needed for the
feature; the round-trip test needs a small test-only parser (split chunks, read accessors) that proves counts, the
exp/sigmoid maths, the SH regrouping and the xyzw order against a `SplatCloud` fixture.
No viewer code is needed; the layout is from the spec.

## Recommendation

Defer SPZ-in-glTF, file one writer card now. The base extension is ratified (registry commit 3 Sep 2026, README status
[V]), its layout maps almost one-to-one onto our PLY fields, and because glTF is W the writer is simpler than the PLY one
(no axis flip, no SH signs): exp on scale, sigmoid on opacity, regroup `f_rest` by coefficient, quaternion to x,y,z,w, in
float32. Cesium and PlayCanvas read it today. The SPZ companion is still a draft with
no folder on `main`: look again when it reaches the Ratified list. ADR 0007's "watch only" row should change to "export
only, float32" in the same card. Filed as a proposal: the `splat-io` writer plus the round-trip test; the Export-sheet row
waits for #294.
