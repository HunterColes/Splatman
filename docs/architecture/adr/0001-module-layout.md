# ADR 0001: module layout

Status: accepted (2026-11-01, council #2)

## Decision

Three new **pure Kotlin/JVM modules** (plugin `org.jetbrains.kotlin.jvm`, bytecode 17, JUnit 5, no Android
imports) hold everything that is maths or bytes:

| Module | Package root | Holds | Depends on |
|---|---|---|---|
| `splat-core` | `com.huntercoles.splatman.splat` | `math` (Vec3, Quat, Mat3, Mat4), `frames` (FrameConversions), `model` (SplatCloud, CameraIntrinsics, CameraPose, Keyframe, SeedPoints), `packet` (stage packets), `sh`, `projection`, `sort`, `capture` (keyframe selection, seed accumulation) | nothing |
| `splat-io` | `com.huntercoles.splatman.splat.io` | `ply`, `splat` (.splat), `spz`, `bundle` (capture bundle) | `splat-core`, kotlinx.serialization |
| `splat-train` | `com.huntercoles.splatman.splat.train` | `init` (seed to SplatCloud), `raster` (CPU forward/backward), `optim` (Adam, densify), `Trainer` interface | `splat-core` |

The Android modules wrap them:

| Module | Role after the rebuild |
|---|---|
| `capture-feature` | ARCore session, frame source, recording to a capture bundle, the processing runner (training in the background) |
| `viewer-feature` | GLES 3.0 splat renderer, orbit camera, viewer screen, export actions |
| `library-feature` | Scan records (Room) and files, the library screen |
| `core` | design system, navigation, coroutine modules (poker leftovers deleted) |
| `app` | wiring |

The existing `viewer-feature/.../rendering/math` (Matrix4, Quaternion, Vector3) moves into `splat-core/math` and
is rewritten with tests there; nothing else keeps a second copy of the maths.

## Gate

`tools/check.sh` runs `./gradlew assembleDebug testDebugUnitTest lintDebug`. A JVM module has `test`, not
`testDebugUnitTest`, so each JVM module's `build.gradle.kts` registers an alias task
`testDebugUnitTest` that depends on `test`. Without it the gate would silently skip their tests. The scaffold card
proves the alias with a deliberately failing test run locally before it lands.

## Why

Fast JVM tests for the parts most likely to be subtly wrong (frames, formats, SH, covariance, gradients); no
Robolectric; a hard wall that keeps Android types out of the data model.

## Rejected

- One `core` Android module for everything: slow tests, Android types leak into the model.
- A module per format: too many build files for a few hundred lines each.
