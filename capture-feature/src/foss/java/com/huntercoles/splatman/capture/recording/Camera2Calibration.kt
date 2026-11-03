package com.huntercoles.splatman.capture.recording

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import com.huntercoles.splatman.splat.io.bundle.CalibrationIntrinsics
import com.huntercoles.splatman.splat.io.bundle.CameraCalibration
import com.huntercoles.splatman.splat.io.bundle.ImageSize
import com.huntercoles.splatman.splat.io.bundle.IntrinsicsSource
import com.huntercoles.splatman.splat.io.bundle.TimestampSource
import timber.log.Timber
import kotlin.math.max
import kotlin.math.tan

/**
 * The `CameraCharacteristics` keys `calibration.json` needs, as plain values, so tests feed them without a device.
 * Sizes are `[w,h]`, rectangles `[l,t,r,b]`, everything else as Camera2 gives it; null when not reported.
 */
interface CharacteristicsView {
    val sensorOrientation: Int?

    /** `SENSOR_INFO_TIMESTAMP_SOURCE` as its integer value. */
    val timestampSource: Int?
    val pixelArraySize: IntArray?
    val activeArray: IntArray?
    val preCorrectionActiveArray: IntArray?
    val physicalSizeMm: FloatArray?
    val focalLengthsMm: FloatArray?
    val lensIntrinsicCalibration: FloatArray?
    val lensDistortion: FloatArray?
    val lensPoseRotation: FloatArray?
    val lensPoseTranslation: FloatArray?
    val lensPoseReference: Int?

    companion object {
        /** Reads [c] once per key. */
        fun of(c: CameraCharacteristics): CharacteristicsView = object : CharacteristicsView {
            override val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
            override val timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            override val pixelArraySize = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?.let { intArrayOf(it.width, it.height) }
            override val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            override val preCorrectionActiveArray =
                c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    ?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            override val physicalSizeMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { floatArrayOf(it.width, it.height) }
            override val focalLengthsMm: FloatArray? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            override val lensIntrinsicCalibration: FloatArray? = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            override val lensDistortion: FloatArray? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.get(CameraCharacteristics.LENS_DISTORTION) else null
            override val lensPoseRotation: FloatArray? = c.get(CameraCharacteristics.LENS_POSE_ROTATION)
            override val lensPoseTranslation: FloatArray? = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
            override val lensPoseReference: Int? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.get(CameraCharacteristics.LENS_POSE_REFERENCE) else null
        }
    }
}

/**
 * Builds `calibration.json` (ADR 0007) from what Camera2 reports for one camera. The image is taken to be the
 * sensor's output scaled to cover [ImageSize] and centre-cropped (Camera2's stream behaviour without a crop region).
 */
object Camera2Calibration {
    /** `CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`. */
    private const val TIMESTAMP_SOURCE_REALTIME = 1

    /** Horizontal field of view assumed when Camera2 reports neither calibration nor focal length. */
    private const val FALLBACK_HFOV_DEG = 65.0

    fun read(characteristics: CharacteristicsView, cameraId: String, image: ImageSize): CameraCalibration {
        val c = characteristics
        val source = if (c.timestampSource == TIMESTAMP_SOURCE_REALTIME) TimestampSource.REALTIME else TimestampSource.UNKNOWN
        return CameraCalibration(
            cameraId = cameraId,
            sensorOrientationDeg = c.sensorOrientation ?: 0,
            timestampSource = source,
            pixelArraySize = c.pixelArraySize?.toList(),
            activeArray = c.activeArray?.toList(),
            preCorrectionActiveArray = c.preCorrectionActiveArray?.toList(),
            physicalSizeMm = c.physicalSizeMm?.toList(),
            focalLengthsMm = c.focalLengthsMm?.toList(),
            lensIntrinsicCalibration = c.lensIntrinsicCalibration?.toList(),
            lensDistortion = c.lensDistortion?.toList(),
            lensPoseRotation = c.lensPoseRotation?.toList(),
            lensPoseTranslation = c.lensPoseTranslation?.toList(),
            lensPoseReference = c.lensPoseReference,
            image = image,
            intrinsics = fromCalibration(c, image) ?: fromFocalLength(c, image),
        )
    }

    /** One line holding every field, nulls written as `null`; logged and returned. */
    fun log(calibration: CameraCalibration): String {
        val k = calibration.intrinsics
        val line = with(calibration) {
            "Camera2Calibration camera_id=$cameraId sensor_orientation_deg=$sensorOrientationDeg " +
                "timestamp_source=$timestampSource pixel_array_size=$pixelArraySize active_array=$activeArray " +
                "pre_correction_active_array=$preCorrectionActiveArray physical_size_mm=$physicalSizeMm " +
                "focal_lengths_mm=$focalLengthsMm lens_intrinsic_calibration=$lensIntrinsicCalibration " +
                "lens_distortion=$lensDistortion lens_pose_rotation=$lensPoseRotation " +
                "lens_pose_translation=$lensPoseTranslation lens_pose_reference=$lensPoseReference " +
                "image=${image.w}x${image.h} fl_x=${k.flX} fl_y=${k.flY} cx=${k.cx} cy=${k.cy} source=${k.source}"
        }
        Timber.i(line)
        return line
    }

    /** `[w,h]` of a `[l,t,r,b]` rectangle, else of the pixel array, else null. */
    private fun extent(rect: IntArray?, pixelArray: IntArray?): Pair<Float, Float>? = when {
        rect != null && rect.size >= 4 && rect[2] > rect[0] && rect[3] > rect[1] ->
            (rect[2] - rect[0]).toFloat() to (rect[3] - rect[1]).toFloat()
        pixelArray != null && pixelArray.size >= 2 && pixelArray[0] > 0 && pixelArray[1] > 0 ->
            pixelArray[0].toFloat() to pixelArray[1].toFloat()
        else -> null
    }

    /** Rescales `LENS_INTRINSIC_CALIBRATION` from pre-correction active-array pixels; null when null or all zero. */
    private fun fromCalibration(c: CharacteristicsView, image: ImageSize): CalibrationIntrinsics? {
        val k = c.lensIntrinsicCalibration ?: return null
        if (k.size < 4 || k[0] <= 0f || k[1] <= 0f) return null
        val (aw, ah) = extent(c.preCorrectionActiveArray, c.pixelArraySize) ?: return null
        val s = max(image.w / aw, image.h / ah)
        val offX = (aw - image.w / s) / 2f
        val offY = (ah - image.h / s) / 2f
        return CalibrationIntrinsics(
            flX = k[0] * s,
            flY = k[1] * s,
            cx = (k[2] - offX) * s,
            cy = (k[3] - offY) * s,
            source = IntrinsicsSource.LENS_INTRINSIC_CALIBRATION,
        )
    }

    /** From the first focal length and the physical size of the pixel array; principal point at the centre. */
    private fun fromFocalLength(c: CharacteristicsView, image: ImageSize): CalibrationIntrinsics {
        val pixels = c.pixelArraySize
        val physical = c.physicalSizeMm
        val f = c.focalLengthsMm?.firstOrNull()?.takeIf { it > 0f }
        val (aw, ah) = extent(c.activeArray, pixels) ?: (image.w.toFloat() to image.h.toFloat())
        val s = max(image.w / aw, image.h / ah)
        val known = f != null && pixels != null && pixels.size >= 2 && physical != null && physical.size >= 2 &&
            physical[0] > 0f && physical[1] > 0f
        val (fx, fy) = if (known) {
            f!! * pixels!![0] / physical!![0] * s to f * pixels[1] / physical[1] * s
        } else {
            val guess = (image.w / 2.0 / tan(Math.toRadians(FALLBACK_HFOV_DEG / 2.0))).toFloat()
            guess to guess
        }
        return CalibrationIntrinsics(
            flX = fx,
            flY = fy,
            cx = image.w / 2f,
            cy = image.h / 2f,
            source = IntrinsicsSource.FOCAL_LENGTH,
        )
    }
}
