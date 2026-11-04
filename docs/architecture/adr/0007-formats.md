# ADR 0007: file formats, import and export

Status: accepted (2026-11-01, council #2)

| Format | Import | Export | When | Frame | Notes |
|---|---|---|---|---|---|
| **3DGS PLY** (INRIA layout) | yes | yes, default | v1, first | F (RDF) | lossless; SH degree 0-3 |
| **`.splat`** (antimatter15) | yes | yes | v1 | F (RDF) | 32 B/splat, SH0 only, lossy |
| **SPZ** (Niantic, v2 and v3) | yes | yes | after PLY | F (RDF), like PLY (#154) | gzip, quantised, ~10x smaller than PLY |
| glTF `KHR_gaussian_splatting` (`.glb`) | no | yes (`GltfWriter`, #414) | ratified 2026 (research #412) | glTF (= W) | export, float32; no `worldToFile`; SPZ-in-glTF deferred (draft) |
| **Capture bundle** (Nerfstudio `transforms.json`) | yes (re-train) | yes (zip) | v1 storage, export later | NS (ADR 0003) | the training dataset |
| **Pose-free capture** (`capture.json`, `imu.csv`, `calibration.json`) | no | later | `foss` Capture (#199) | none | input to pose recovery (#200) |

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

## Pose-free capture

What `foss` Capture writes (epic #199) and pose recovery (#200) reads, so the writer, the recorder and SfM share one
schema.

```
scans/<scan-id>/
  capture.json        # written last; "splatman_capture_version": 1, "poses": "none"
  images/frame_00001.jpg ...   # sensor-oriented, JPEG q90, as for the capture bundle
  imu.csv             # gyroscope and accelerometer samples
  calibration.json    # what Camera2 reported, plus intrinsics for the JPEG size
```

`transforms.json` and `seed.ply` are absent until pose recovery writes them; `transforms.json` keeps meaning "poses
known, trainable" (Nerfstudio needs `transform_matrix`).

Rejected:
- an identity or zero `transform_matrix` in `transforms.json`: a lie the trainer would train on;
- a nullable pose in `BundleFrame`: it ripples through training.

### `capture.json`

Written last (tmp, fsync, rename, like `transforms.json`), so a folder without it is not a capture. Keys:
`splatman_capture_version: 1` (higher is rejected), `poses: "none"`, `imu_file_path: "imu.csv"`,
`calibration_file_path: "calibration.json"`, `timestamp_source: "REALTIME" | "UNKNOWN"`, optional `subject` (absent:
no subject without poses), and `frames: [{file_path, timestamp_ns, w, h, sharpness, fl_x?, fl_y?, cx?, cy?,
exposure_ns?}]`. Per-frame intrinsics only when the CaptureResult reported `LENS_INTRINSIC_CALIBRATION`, rescaled to
the JPEG's pixels. Unknown keys are ignored on read.

```json
{
  "splatman_capture_version": 1,
  "poses": "none",
  "imu_file_path": "imu.csv",
  "calibration_file_path": "calibration.json",
  "timestamp_source": "REALTIME",
  "frames": [
    {"file_path": "images/frame_00001.jpg", "timestamp_ns": 81234567890123, "w": 1920, "h": 1080,
     "sharpness": 412.7, "fl_x": 1498.2, "fl_y": 1497.9, "cx": 961.4, "cy": 539.0, "exposure_ns": 8333333},
    {"file_path": "images/frame_00002.jpg", "timestamp_ns": 81234901223456, "w": 1920, "h": 1080,
     "sharpness": 398.1}
  ]
}
```

### `imu.csv`

Header `timestamp_ns,sensor,x,y,z`; `sensor` is `g` (`TYPE_GYROSCOPE`, rad/s) or `a` (`TYPE_ACCELEROMETER`, m/s^2,
gravity included). Axes are Android's sensor axes of the device (not the camera); numbers use `Locale.ROOT`; rows are
in arrival order, timestamps non-decreasing per sensor. Sampling is 100 Hz or more.

```
timestamp_ns,sensor,x,y,z
81234560012345,g,0.0123,-0.0041,0.0007
81234560498765,a,0.112,9.791,0.304
81234565012345,g,0.0119,-0.0044,0.0009
```

### Timestamps

Frames use `SENSOR_TIMESTAMP`, IMU rows use `SensorEvent.timestamp`. When `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME
both are on `elapsedRealtimeNanos`; otherwise `timestamp_source` is `UNKNOWN` and readers must not assume a shared
clock.

### `calibration.json`

What Camera2 reported, with nulls kept explicit so missing fields show: `camera_id`, `sensor_orientation_deg`,
`timestamp_source`, `pixel_array_size [w,h]`, `active_array [l,t,r,b]`, `pre_correction_active_array [l,t,r,b]`,
`physical_size_mm [w,h]`, `focal_lengths_mm [...]`, `lens_intrinsic_calibration [fx,fy,cx,cy,s] | null`,
`lens_distortion [k1,k2,k3,p1,p2] | null`, `lens_pose_rotation [x,y,z,w] | null`, `lens_pose_translation [x,y,z] |
null`, `lens_pose_reference | null`, plus `image {w,h}` and `intrinsics {fl_x,fl_y,cx,cy,source}` for the JPEG size.
`source` is `LENS_INTRINSIC_CALIBRATION` (rescaled from pre-correction pixels) or `FOCAL_LENGTH` (from focal length and
physical size, when the calibration is null or all zero).

```json
{
  "camera_id": "0",
  "sensor_orientation_deg": 90,
  "timestamp_source": "REALTIME",
  "pixel_array_size": [4032, 3024],
  "active_array": [0, 0, 4032, 3024],
  "pre_correction_active_array": [0, 0, 4032, 3024],
  "physical_size_mm": [5.645, 4.234],
  "focal_lengths_mm": [4.38],
  "lens_intrinsic_calibration": null,
  "lens_distortion": null,
  "lens_pose_rotation": null,
  "lens_pose_translation": null,
  "lens_pose_reference": null,
  "image": {"w": 1920, "h": 1080},
  "intrinsics": {"fl_x": 1489.6, "fl_y": 1489.6, "cx": 960.0, "cy": 540.0, "source": "FOCAL_LENGTH"}
}
```

## Import rules

Detect by content, not extension: `ply\n` magic, `NGSP` after gunzip, else `.splat` when size % 32 == 0. Large
files follow the SH memory cap in ADR 0002.
