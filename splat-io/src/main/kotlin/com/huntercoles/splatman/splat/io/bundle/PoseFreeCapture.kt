package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The pose-free capture's manifest (ADR 0007), written last. */
const val CAPTURE_FILE: String = "capture.json"

/** Gyroscope and accelerometer samples (ADR 0007 `imu.csv`). */
const val IMU_FILE: String = "imu.csv"

/** What Camera2 reported (ADR 0007 `calibration.json`). */
const val CALIBRATION_FILE: String = "calibration.json"

/** The pose-free capture version this build writes and the only one it reads (`splatman_capture_version`). */
const val SPLATMAN_CAPTURE_VERSION: Int = 1

/** Which clock frame and IMU timestamps are on (Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`). */
@Serializable
enum class TimestampSource {
    /** Both on `elapsedRealtimeNanos`: frames and IMU share a clock. */
    REALTIME,

    /** No shared clock may be assumed. */
    UNKNOWN,
}

/** Where `calibration.json`'s JPEG intrinsics came from. */
@Serializable
enum class IntrinsicsSource {
    /** Rescaled from `LENS_INTRINSIC_CALIBRATION` (pre-correction pixels). */
    LENS_INTRINSIC_CALIBRATION,

    /** From focal length and physical sensor size (calibration null or all zero). */
    FOCAL_LENGTH,
}

/** One frame handed to [PoseFreeCaptureWriter.writeFrame]: metadata plus the encoded JPEG (sensor orientation). */
data class PoseFreeFrameInput(
    /** `SENSOR_TIMESTAMP`, nanoseconds. */
    val timestampNs: Long,
    /** JPEG width, pixels. */
    val width: Int,
    /** JPEG height, pixels. */
    val height: Int,
    /** Sharpness score of the frame, finite, unitless. */
    val sharpness: Float,
    /** JPEG bytes (q90); not copied. */
    val jpeg: ByteArray,
    /** Intrinsics in the JPEG's pixels, only when the CaptureResult reported `LENS_INTRINSIC_CALIBRATION`. */
    val intrinsics: CameraIntrinsics?,
    /** `SENSOR_EXPOSURE_TIME`, nanoseconds, when reported. */
    val exposureNs: Long?,
)

/**
 * One frame as written to or read from `capture.json`. Per-frame intrinsics are stored as `fl_x, fl_y, cx, cy` only:
 * read back, their size is the frame's and their distortion is none.
 */
data class FrameEntry(
    /** Image path relative to the capture directory. */
    val filePath: String,
    /** `SENSOR_TIMESTAMP`, nanoseconds. */
    val timestampNs: Long,
    /** Image width, pixels. */
    val width: Int,
    /** Image height, pixels. */
    val height: Int,
    /** Sharpness score, unitless. */
    val sharpness: Float,
    /** Intrinsics in the image's pixels, or null. */
    val intrinsics: CameraIntrinsics?,
    /** Exposure time, nanoseconds, or null. */
    val exposureNs: Long?,
)

/** A pose-free frame read back by [PoseFreeCaptureReader]; its image file was checked to exist. */
data class PoseFreeFrame(
    /** The frame as `capture.json` lists it. */
    val entry: FrameEntry,
    /** The frame's image file. */
    val imageFile: File,
)

/** A pose-free capture read from disk (ADR 0007). */
data class PoseFreeCapture(
    /** The capture directory. */
    val directory: File,
    /** Its `splatman_capture_version`. */
    val version: Int,
    /** Whether frame and IMU timestamps share a clock. */
    val timestampSource: TimestampSource,
    /** Frames in file order. */
    val frames: List<PoseFreeFrame>,
    /** The IMU samples file, checked to exist; parse with [ImuCsvReader]. */
    val imuFile: File,
    /** The parsed `calibration.json`. */
    val calibration: CameraCalibration,
    /** Object or room scan (`mode`); OBJECT when the key is absent or unknown. */
    val mode: ScanMode = ScanMode.OBJECT,
)

/** `image` of `calibration.json`: the JPEG size, pixels. */
@Serializable
data class ImageSize(
    val w: Int,
    val h: Int,
)

/** `intrinsics` of `calibration.json`: intrinsics for the JPEG size, pixels. */
@Serializable
data class CalibrationIntrinsics(
    @SerialName("fl_x") val flX: Float,
    @SerialName("fl_y") val flY: Float,
    val cx: Float,
    val cy: Float,
    val source: IntrinsicsSource,
)

/**
 * `calibration.json` (ADR 0007): what Camera2 reported, nulls kept explicit on write. Field order is the file's key
 * order. Arrays are as Camera2 gives them: sizes `[w,h]`, rectangles `[l,t,r,b]`, calibration `[fx,fy,cx,cy,s]`,
 * distortion `[k1,k2,k3,p1,p2]`, rotation `[x,y,z,w]`, translation `[x,y,z]` metres.
 */
@Serializable
data class CameraCalibration(
    @SerialName("camera_id") val cameraId: String,
    @SerialName("sensor_orientation_deg") val sensorOrientationDeg: Int,
    @SerialName("timestamp_source") val timestampSource: TimestampSource,
    @SerialName("pixel_array_size") val pixelArraySize: List<Int>?,
    @SerialName("active_array") val activeArray: List<Int>?,
    @SerialName("pre_correction_active_array") val preCorrectionActiveArray: List<Int>?,
    @SerialName("physical_size_mm") val physicalSizeMm: List<Float>?,
    @SerialName("focal_lengths_mm") val focalLengthsMm: List<Float>?,
    @SerialName("lens_intrinsic_calibration") val lensIntrinsicCalibration: List<Float>?,
    @SerialName("lens_distortion") val lensDistortion: List<Float>?,
    @SerialName("lens_pose_rotation") val lensPoseRotation: List<Float>?,
    @SerialName("lens_pose_translation") val lensPoseTranslation: List<Float>?,
    /** Camera2 `LENS_POSE_REFERENCE` as its integer value. */
    @SerialName("lens_pose_reference") val lensPoseReference: Int?,
    val image: ImageSize,
    val intrinsics: CalibrationIntrinsics,
)

/** On-disk `capture.json`; field order is the file's key order. */
@Serializable
internal data class CaptureJson(
    @SerialName("splatman_capture_version") val version: Int,
    val poses: String = POSES_NONE,
    @SerialName("imu_file_path") val imuFilePath: String = IMU_FILE,
    @SerialName("calibration_file_path") val calibrationFilePath: String = CALIBRATION_FILE,
    @SerialName("timestamp_source") val timestampSource: TimestampSource,
    /** The [ScanMode] key; written only for a room scan, absent means object. */
    val mode: String? = null,
    val frames: List<CaptureFrameJson>,
)

@Serializable
internal data class CaptureFrameJson(
    @SerialName("file_path") val filePath: String,
    @SerialName("timestamp_ns") val timestampNs: Long,
    val w: Int,
    val h: Int,
    val sharpness: Float,
    @SerialName("fl_x") val flX: Float? = null,
    @SerialName("fl_y") val flY: Float? = null,
    val cx: Float? = null,
    val cy: Float? = null,
    @SerialName("exposure_ns") val exposureNs: Long? = null,
)

internal const val POSES_NONE = "none"

/** `calibration.json` keeps nulls explicit so missing Camera2 fields show. */
internal val calibrationJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
