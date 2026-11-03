package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Reads a pose-free capture (ADR 0007). Unknown keys are ignored; a `splatman_capture_version` other than
 * [SPLATMAN_CAPTURE_VERSION] is rejected; every image, the IMU file and the calibration file must exist, else the
 * error names it. Image bytes are not loaded.
 */
object PoseFreeCaptureReader {

    /** True when [directory] holds a finished pose-free capture: `capture.json` present, `transforms.json` absent. */
    fun isPoseFreeCapture(directory: File): Boolean =
        File(directory, CAPTURE_FILE).isFile && !File(directory, TRANSFORMS_FILE).exists()

    @Throws(CaptureBundleException::class)
    fun read(directory: File): PoseFreeCapture {
        val file = File(directory, CAPTURE_FILE)
        if (!file.isFile) throw CaptureBundleException("not a pose-free capture: no $CAPTURE_FILE in ${directory.path}")
        val text = file.readText(Charsets.UTF_8)
        val doc = decode(CAPTURE_FILE) {
            checkVersion(bundleJson.parseToJsonElement(text) as? JsonObject)
            bundleJson.decodeFromString(CaptureJson.serializer(), text)
        }
        if (doc.poses != POSES_NONE) {
            throw CaptureBundleException("$CAPTURE_FILE: poses must be \"$POSES_NONE\", got \"${doc.poses}\"")
        }
        val frames = doc.frames.mapIndexed { i, f -> readFrame(directory, i, f) }
        val imu = existing(directory, doc.imuFilePath, "imu_file_path")
        val calFile = existing(directory, doc.calibrationFilePath, "calibration_file_path")
        val calibration = decode(doc.calibrationFilePath) {
            calibrationJson.decodeFromString(CameraCalibration.serializer(), calFile.readText(Charsets.UTF_8))
        }
        return PoseFreeCapture(directory, doc.version, doc.timestampSource, frames, imu, calibration)
    }

    private fun checkVersion(root: JsonObject?) {
        val key = "splatman_capture_version"
        val v = (root?.get(key) as? JsonPrimitive)?.intOrNull
            ?: throw CaptureBundleException("$CAPTURE_FILE: $key missing or not an integer")
        if (v != SPLATMAN_CAPTURE_VERSION) {
            throw CaptureBundleException(
                "$CAPTURE_FILE: $key $v is not supported (this build reads $SPLATMAN_CAPTURE_VERSION)",
            )
        }
    }

    private inline fun <T> decode(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: CaptureBundleException) {
            throw e
        } catch (e: SerializationException) {
            throw CaptureBundleException("$name: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CaptureBundleException("$name: ${e.message}", e)
        }

    private fun readFrame(directory: File, index: Int, f: CaptureFrameJson): PoseFreeFrame {
        val where = "frames[$index] (${f.filePath})"
        if (!f.sharpness.isFinite()) throw CaptureBundleException("$where: sharpness must be finite")
        val fx = f.flX
        val fy = f.flY
        val cx = f.cx
        val cy = f.cy
        val given = listOf(fx, fy, cx, cy).count { it != null }
        if (given != 0 && given != 4) throw CaptureBundleException("$where: fl_x, fl_y, cx, cy must come together")
        val intrinsics = if (fx != null && fy != null && cx != null && cy != null) {
            try {
                CameraIntrinsics(fx, fy, cx, cy, f.w, f.h)
            } catch (e: IllegalArgumentException) {
                throw CaptureBundleException("$where: ${e.message}", e)
            }
        } else {
            null
        }
        val image = existing(directory, f.filePath, "$where file_path")
        val entry = FrameEntry(f.filePath, f.timestampNs, f.w, f.h, f.sharpness, intrinsics, f.exposureNs)
        return PoseFreeFrame(entry, image)
    }

    private fun existing(directory: File, path: String, key: String): File {
        val file = File(directory, path)
        if (!file.isFile) throw CaptureBundleException("$key: missing file $path")
        return file
    }
}
