package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.capture.ScanMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Writes a pose-free capture (ADR 0007): frames as they are kept, `calibration.json`, and last `capture.json`.
 * `imu.csv` is streamed by [ImuCsvWriter]. Every file goes through a synced `.tmp` and a rename, and any old
 * `capture.json` is removed before other files are written, so a folder killed before [finish] is not a capture.
 */
object PoseFreeCaptureWriter {

    /** Writes [frame]'s JPEG as `images/frame_{index+1}.jpg` (5 digits) and returns its `capture.json` entry. */
    @Throws(IOException::class)
    fun writeFrame(directory: File, index: Int, frame: PoseFreeFrameInput): FrameEntry {
        require(index >= 0) { "index must be >= 0, got $index" }
        require(frame.sharpness.isFinite()) { "sharpness must be finite, got ${frame.sharpness}" }
        require(frame.width > 0 && frame.height > 0) { "size must be positive, got ${frame.width}x${frame.height}" }
        require(frame.focusDistanceDpt?.isFinite() != false) { "focus distance must be finite" }
        prepare(directory)
        val path = String.format(Locale.ROOT, "images/frame_%05d.jpg", index + 1)
        CaptureBundleWriter.writeAtomic(File(directory, path), frame.jpeg)
        return FrameEntry(
            filePath = path,
            timestampNs = frame.timestampNs,
            width = frame.width,
            height = frame.height,
            sharpness = frame.sharpness,
            intrinsics = frame.intrinsics,
            exposureNs = frame.exposureNs,
            rollingShutterSkewNs = frame.rollingShutterSkewNs,
            focusDistanceDpt = frame.focusDistanceDpt,
        )
    }

    /** Writes `calibration.json`, nulls explicit. */
    @Throws(IOException::class)
    fun writeCalibration(directory: File, calibration: CameraCalibration) {
        prepare(directory)
        CaptureBundleWriter.writeAtomic(
            File(directory, CALIBRATION_FILE),
            encodeCalibration(calibration).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Writes `capture.json` listing [frames], last; the folder is a capture from here on. [mode] is written as `mode`
     * only when it is not OBJECT; [stream] (from [TrackingStreamWriter.info]) as `stream` only when given.
     */
    @Throws(IOException::class)
    fun finish(
        directory: File,
        frames: List<FrameEntry>,
        timestampSource: TimestampSource,
        mode: ScanMode = ScanMode.OBJECT,
        stream: TrackingStreamInfo? = null,
    ) {
        frames.forEach { require(it.sharpness.isFinite()) { "${it.filePath}: sharpness must be finite" } }
        prepare(directory)
        val doc = CaptureJson(
            version = SPLATMAN_CAPTURE_VERSION,
            timestampSource = timestampSource,
            mode = CaptureBundleWriter.storedModeKey(mode),
            frames = frames.map { f ->
                val k = f.intrinsics
                CaptureFrameJson(
                    filePath = f.filePath,
                    timestampNs = f.timestampNs,
                    w = f.width,
                    h = f.height,
                    sharpness = f.sharpness,
                    flX = k?.fx,
                    flY = k?.fy,
                    cx = k?.cx,
                    cy = k?.cy,
                    exposureNs = f.exposureNs,
                    rollingShutterSkewNs = f.rollingShutterSkewNs,
                    focusDistanceDpt = f.focusDistanceDpt,
                )
            },
            stream = stream,
        )
        CaptureBundleWriter.writeAtomic(File(directory, CAPTURE_FILE), encodeCapture(doc).toByteArray(Charsets.UTF_8))
    }

    internal fun encodeCapture(doc: CaptureJson): String =
        bundleJson.encodeToString(CaptureJson.serializer(), doc) + "\n"

    internal fun encodeCalibration(calibration: CameraCalibration): String =
        calibrationJson.encodeToString(CameraCalibration.serializer(), calibration) + "\n"

    private fun prepare(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.path}")
        Files.deleteIfExists(File(directory, CAPTURE_FILE).toPath())
    }
}
