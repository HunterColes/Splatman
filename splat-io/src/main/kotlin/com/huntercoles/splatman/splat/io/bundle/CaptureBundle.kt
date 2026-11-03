package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.SeedPoints
import java.io.File
import java.io.IOException

/** The bundle format version this build writes and the highest it reads (ADR 0007 `splatman_version`). */
const val SPLATMAN_BUNDLE_VERSION: Int = 1

/** One frame handed to [CaptureBundleWriter]: metadata plus the already-encoded JPEG (sensor orientation). */
class BundleFrameInput(
    /** Exposure time, nanoseconds on the ARCore frame clock. */
    val timestampNs: Long,
    /** Intrinsics of the JPEG, pixels of the JPEG. */
    val intrinsics: CameraIntrinsics,
    /** `T_W_Cgl` at exposure. */
    val pose: CameraPose,
    /** JPEG bytes (q90 by ADR 0007); not copied. */
    val jpeg: ByteArray,
    /** Optional depth, written as little-endian f32 metres. */
    val depth: DepthImage? = null,
)

/** One frame read back by [CaptureBundleReader]; its files were checked to exist. */
data class BundleFrame(
    /** Exposure time, nanoseconds. */
    val timestampNs: Long,
    /** Intrinsics of [imageFile], pixels. */
    val intrinsics: CameraIntrinsics,
    /** `T_W_Cgl`, unit quaternion with `w >= 0`. */
    val pose: CameraPose,
    /** The frame's image file (`file_path`). */
    val imageFile: File,
    /** The frame's depth file (`depth_file_path`), or null. */
    val depthFile: File?,
    /** Intrinsics of the depth image, or null when there is no depth or the file does not give them. */
    val depthIntrinsics: CameraIntrinsics?,
)

/** A capture bundle read from disk (ADR 0007). */
class CaptureBundle(
    /** The bundle directory. */
    val directory: File,
    /** Its `splatman_version`. */
    val version: Int,
    /** Frames in file order. */
    val frames: List<BundleFrame>,
    /** The seed points from `ply_file_path` (frame W), or null when the bundle names none. */
    val seed: SeedPoints?,
    /** The capture subject (the point the coverage ring circled), frame W, metres; null when the bundle has none. */
    val subject: Vec3? = null,
    /** Object or room scan (`splatman_mode`); OBJECT when the key is absent or unknown. */
    val mode: ScanMode = ScanMode.OBJECT,
)

/** A bundle that cannot be read: the message names the file or key at fault. */
class CaptureBundleException(message: String, cause: Throwable? = null) : IOException(message, cause)
