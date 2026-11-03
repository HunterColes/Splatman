# ADR 0007: file formats, import and export

Status: accepted (2026-11-01, council #2)

| Format | Import | Export | When | Frame | Notes |
|---|---|---|---|---|---|
| **3DGS PLY** (INRIA layout) | yes | yes, default | v1, first | F (RDF) | lossless; SH degree 0-3 |
| **`.splat`** (antimatter15) | yes | yes | v1 | F (RDF) | 32 B/splat, SH0 only, lossy |
| **SPZ** (Niantic, v2 and v3) | yes | yes | after PLY | F (RDF), like PLY (#154) | gzip, quantised, ~10x smaller than PLY |
| glTF `KHR_gaussian_splatting` | later | later | when Khronos ratifies it | glTF (= W) | watch only |
| **Capture bundle** (Nerfstudio `transforms.json`) | yes (re-train) | yes (zip) | v1 storage, export later | NS (ADR 0003) | the training dataset |

All codecs live in `splat-io`, read from an `InputStream`/`ByteArray` and write to an `OutputStream`, with no
Android types, and return/accept `SplatCloud` in frame W. The frame change happens inside each codec via
`FrameConversions` (ADR 0003).

## 3DGS PLY

- Header: `ply`, `format binary_little_endian 1.0`, `element vertex N`, then `property float` for, in this order:
  `x y z nx ny nz f_dc_0 f_dc_1 f_dc_2 f_rest_0..f_rest_{3K-1} opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3`,
  `end_header\n`. `K = (degree+1)^2 - 1`, so 0, 9, 24 or 45 `f_rest` fields. Normals are written as 0.
- Values are raw: `opacity` is a logit, `scale_*` natural logs, `rot_*` = w,x,y,z (not necessarily unit),
  `f_rest_{c*K + k}` is channel `c`, coefficient `k`. This is `SplatCloud`'s own layout, so the codec only converts
  the frame.
- The reader accepts any property order and extra properties (looked up by name), `float`/`double`, and rejects
  ASCII and big-endian with a clear error (v1). It reads the degree from the number of `f_rest` fields.
- Writer output is byte-identical across runs (fixed header text, no comments).

## `.splat`

32 bytes per splat, little-endian, no header: position 3 x f32, scale 3 x f32 (`exp(logScale)`), colour 4 x u8
(`clamp((0.5 + C0*shDc) * 255)`, alpha `sigmoid(opacityLogit) * 255`), rotation 4 x u8 (unit quaternion w,x,y,z, each
`clamp(q * 128 + 128)`). Count = file size / 32. Import inverts each step (`logit` of alpha clamped to
[1/255, 254/255]).

## SPZ

Follow Niantic's spz reference (MIT, github.com/nianticlabs/spz) for the header (magic `NGSP`, version, count, SH
degree, fractional bits, flags), the gzip stream and every quantisation; support versions 2 and 3 on import, write
version 3. Coordinates are stored in F, the PLY frame (ADR 0003 rule 5): converters keep the PLY's raw values
(the bundled cactus.spz matches the cactus PLY's raw coordinates, #154), so the codec converts positions,
quaternions and SH signs W <-> F through `FrameConversions`. Tests compare against a sample file and state per-field tolerances from the quantisation steps.

## Capture bundle (on disk, per scan)

```
scans/<scan-id>/
  transforms.json     # Nerfstudio layout + "splatman_version": 1
  images/frame_00001.jpg ...   # sensor-oriented, JPEG q90
  depth/frame_00001.bin ...    # optional, little-endian f32 metres, with "depth_file_path" per frame
  seed.ply            # SeedPoints as x y z red green blue (u8); "ply_file_path": "seed.ply"
  splat.ply           # the trained result (3DGS PLY)
  splat.uncropped.ply # optional, written by crop: the original before the first Apply (ADR 0011)
```

`transforms.json`: top-level `camera_model: "OPENCV"`, per frame `file_path`, `transform_matrix` (`T_W_Cgl`,
row-major 4x4), and per-frame `fl_x, fl_y, cx, cy, w, h` (intrinsics can change between frames), plus
`timestamp_ns`. `splat.uncropped.ply` and the crop order: [ADR 0011](0011-crop.md). Unknown keys are ignored on read; `splatman_version` higher than known is rejected.

## Import rules

Detect by content, not extension: `ply\n` magic, `NGSP` after gunzip, else `.splat` when size % 32 == 0. Large
files follow the SH memory cap in ADR 0002.
