# ADR 0002: core types and packets

Status: accepted (2026-11-01, council #2)

All types live in `splat-core` (`com.huntercoles.splatman.splat.model` and `.packet`). Lengths are metres, angles
radians, time nanoseconds (`timestampNs`, the ARCore `Frame.getTimestamp()` clock), pixels are pixels of the image
the type names. Every type that is persisted has an `Int` format version.

## Value types

```kotlin
// Hamilton quaternion, components named so order can never be confused. Unit length when it is a rotation.
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float)
data class Vec3(val x: Float, val y: Float, val z: Float)

// Pinhole intrinsics of one image, OpenCV pixel convention: pixel (0,0)'s centre is at (0,0), +u right, +v down.
data class CameraIntrinsics(
    val fx: Float, val fy: Float,      // focal lengths, px
    val cx: Float, val cy: Float,      // principal point, px
    val width: Int, val height: Int,   // image size, px
    val distortion: Distortion = Distortion.NONE, // OpenCV k1,k2,p1,p2,k3; ARCore images are undistorted: NONE
) { fun scaled(newWidth: Int, newHeight: Int): CameraIntrinsics }

// Rigid transform T_world_camera: maps a point in the camera frame (OpenGL camera axes, ADR 0003) to the world.
data class CameraPose(val rotation: Quat, val translationM: Vec3)
```

`scaled()` keeps the pixel-centre convention: with `s = newWidth / width`, `fx' = fx * s` and
`cx' = (cx + 0.5) * s - 0.5` (same for y).

`Distortion` exists so the calibration research (#3) can fill it later; v1 always passes `NONE` (ARCore's CPU
image intrinsics describe an undistorted pinhole for the image it gives).

## SplatCloud (struct of arrays)

```kotlin
class SplatCloud(
    val count: Int,
    val shDegree: Int,                 // 0..3
    val positions: FloatArray,         // 3N, world frame W, metres
    val logScales: FloatArray,         // 3N, natural log of the per-axis standard deviation, metres
    val rotations: FloatArray,         // 4N, quaternion w,x,y,z per splat, local -> world, not necessarily unit
    val opacityLogits: FloatArray,     // N, opacity = sigmoid(value)
    val shDc: FloatArray,              // 3N, degree-0 SH per channel (r,g,b): colour = 0.5 + C0 * value
    val shRest: FloatArray,            // N * 3 * K, K = (shDegree+1)^2 - 1; layout [splat][channel][coeff]
)
```

- Raw parameters, as the optimiser and the PLY file hold them; activation happens at use (`sh`, `projection`,
  the renderer's packer). Accessors (`opacity(i)`, `scale(i)`, `rotation(i)`) do the activation for readers.
- `shRest` layout per splat is channel-major, `[c * K + k]`, the same order as the PLY `f_rest_*` fields, so PLY
  I/O is a straight copy.
- The constructor checks every array length against `count` and `shDegree` and rejects mismatches.
- Immutable by contract: no code writes into a published cloud's arrays. The trainer keeps its own working buffers
  and publishes snapshots (copies) at intervals. Tests can rely on equality via `contentEquals`.
- Memory cap: the importer keeps SH rest only while `count * 3 * K * 4` bytes stays under 64 MB; above that it
  drops to degree 0 and says so in the import result.

## Frames and keyframes

```kotlin
data class Keyframe(
    val id: Int,
    val timestampNs: Long,
    val intrinsics: CameraIntrinsics,   // of `image`
    val pose: CameraPose,               // T_world_camera at exposure
    val image: RgbImage,                // RGBA8888 row-major, sensor orientation, width x height = intrinsics
    val depth: DepthImage?,             // metres as Float32 or null; own intrinsics (depth is lower resolution)
)
class RgbImage(val width: Int, val height: Int, val rgba: ByteArray)
class DepthImage(val intrinsics: CameraIntrinsics, val depthM: FloatArray, val confidence: ByteArray?)
class SeedPoints(val count: Int, val positions: FloatArray /* 3N world m */, val colours: ByteArray /* 3N sRGB */)
```

## Packets between stages

Each stage consumes and emits one packet type; packets are immutable and carry `version`.

| From -> to | Packet | Contents | Lives |
|---|---|---|---|
| tracking -> recorder | `TrackedFrame` | timestampNs, trackingState, pose, intrinsics, a handle to the CPU image (closed by the recorder) | transient, one frame |
| recorder -> disk | `CaptureBundle` | version, capture id, keyframes (images as JPEG files), seed points, world frame note | on disk, ADR 0007 |
| bundle -> trainer | `TrainingInput` | keyframes at training resolution, seed points, `TrainingConfig` (iterations, max splats, SH degree, image long side) | in memory |
| trainer -> UI/viewer | `TrainingProgress` | iteration, totalIterations, loss, splatCount, elapsedMs, optional `SplatCloud` snapshot | `Flow`, conflated |
| trainer -> library | `TrainingResult` | final `SplatCloud`, stats | written as PLY |
| library -> viewer | `SplatCloud` | | loaded from file |
| viewer -> exporter | `ExportRequest` | cloud, `ExportFormat` (PLY, SPLAT, SPZ), destination | one call |

`TrackedFrame` is the only packet holding a platform resource; it is defined in `capture-feature`, not in
`splat-core`, and converted to a `Keyframe` there.

## Rejected

- Array-of-structs `GaussianSplat` objects (the current model): one object per splat, 1M allocations, no bulk copy.
- Activated values in memory (linear scale, 0..1 opacity): the optimiser needs raw values and PLY stores raw values,
  so activated storage would convert twice.
- Direct `ByteBuffer` storage: see the dissent in the council record.
