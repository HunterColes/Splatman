package com.huntercoles.splatman.splat.io.bundle

import com.huntercoles.splatman.splat.capture.ScanMode
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PoseFreeCaptureTest {

    @TempDir
    lateinit var tmp: File

    /** The two frames of ADR 0007's `capture.json` example. */
    private fun goldenFrames() = listOf(
        PoseFreeFrameInput(
            81234567890123L, 1920, 1080, 412.7f, byteArrayOf(1, 2, 3),
            CameraIntrinsics(1498.2f, 1497.9f, 961.4f, 539.0f, 1920, 1080), 8333333L,
        ),
        PoseFreeFrameInput(81234901223456L, 1920, 1080, 398.1f, byteArrayOf(4, 5), null, null),
    )

    /** ADR 0007's `calibration.json` example: every nullable lens field null. */
    private val goldenCalibration = CameraCalibration(
        cameraId = "0",
        sensorOrientationDeg = 90,
        timestampSource = TimestampSource.REALTIME,
        pixelArraySize = listOf(4032, 3024),
        activeArray = listOf(0, 0, 4032, 3024),
        preCorrectionActiveArray = listOf(0, 0, 4032, 3024),
        physicalSizeMm = listOf(5.645f, 4.234f),
        focalLengthsMm = listOf(4.38f),
        lensIntrinsicCalibration = null,
        lensDistortion = null,
        lensPoseRotation = null,
        lensPoseTranslation = null,
        lensPoseReference = null,
        image = ImageSize(1920, 1080),
        intrinsics = CalibrationIntrinsics(1489.6f, 1489.6f, 960f, 540f, IntrinsicsSource.FOCAL_LENGTH),
    )

    /** Writes a complete capture: frames, calibration, an IMU file, then `capture.json`. */
    private fun writeCapture(
        dir: File,
        calibration: CameraCalibration = goldenCalibration,
        source: TimestampSource = TimestampSource.REALTIME,
    ): List<FrameEntry> {
        val entries = goldenFrames().mapIndexed { i, f -> PoseFreeCaptureWriter.writeFrame(dir, i, f) }
        PoseFreeCaptureWriter.writeCalibration(dir, calibration)
        ImuCsvWriter(File(dir, IMU_FILE)).use { it.gyro(1L, 0.1f, 0.2f, 0.3f) }
        PoseFreeCaptureWriter.finish(dir, entries, source)
        return entries
    }

    @Test
    fun `writer round-trips through the reader`() {
        val dir = File(tmp, "scan")
        val calibration = goldenCalibration.copy(
            timestampSource = TimestampSource.UNKNOWN,
            lensIntrinsicCalibration = listOf(3000f, 3001f, 2016f, 1512f, 0f),
            lensDistortion = listOf(0.1f, -0.02f, 0.003f, 0.0004f, -0.0005f),
            lensPoseRotation = listOf(0f, 0f, 0f, 1f),
            lensPoseTranslation = null,
            lensPoseReference = 0,
            intrinsics = CalibrationIntrinsics(1428.5f, 1429f, 960f, 540f, IntrinsicsSource.LENS_INTRINSIC_CALIBRATION),
        )
        val entries = writeCapture(dir, calibration, TimestampSource.UNKNOWN)

        assertTrue(PoseFreeCaptureReader.isPoseFreeCapture(dir))
        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(SPLATMAN_CAPTURE_VERSION, capture.version)
        assertEquals(TimestampSource.UNKNOWN, capture.timestampSource)
        assertEquals(entries, capture.frames.map { it.entry })
        assertEquals(1498.2f, capture.frames[0].entry.intrinsics!!.fx)
        assertEquals(8333333L, capture.frames[0].entry.exposureNs)
        assertNull(capture.frames[1].entry.intrinsics)
        assertNull(capture.frames[1].entry.exposureNs)
        assertArrayEquals(byteArrayOf(1, 2, 3), capture.frames[0].imageFile.readBytes())
        assertArrayEquals(byteArrayOf(4, 5), capture.frames[1].imageFile.readBytes())
        assertEquals(calibration, capture.calibration)
        assertEquals(File(dir, IMU_FILE), capture.imuFile)
    }

    @Test
    fun `calibration with nulls round-trips`() {
        val dir = File(tmp, "nulls")
        writeCapture(dir)
        val read = PoseFreeCaptureReader.read(dir).calibration
        assertEquals(goldenCalibration, read)
        assertNull(read.lensIntrinsicCalibration)
        assertNull(read.lensPoseReference)
    }

    @Test
    fun `capture json matches the golden file byte for byte`() {
        val dir = File(tmp, "golden")
        writeCapture(dir)
        val expected = javaClass.getResource("/bundle/golden_capture.json")!!.readBytes()
        assertEquals(String(expected, Charsets.UTF_8), File(dir, CAPTURE_FILE).readText(Charsets.UTF_8))
        assertArrayEquals(expected, File(dir, CAPTURE_FILE).readBytes())
    }

    @Test
    fun `calibration json matches the golden file byte for byte`() {
        val dir = File(tmp, "golden_cal")
        PoseFreeCaptureWriter.writeCalibration(dir, goldenCalibration)
        val expected = javaClass.getResource("/bundle/golden_calibration.json")!!.readBytes()
        assertEquals(String(expected, Charsets.UTF_8), File(dir, CALIBRATION_FILE).readText(Charsets.UTF_8))
        assertArrayEquals(expected, File(dir, CALIBRATION_FILE).readBytes())
    }

    @Test
    fun `golden files are byte-identical under a comma-decimal default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val dir = File(tmp, "locale")
            writeCapture(dir)
            assertArrayEquals(
                javaClass.getResource("/bundle/golden_capture.json")!!.readBytes(),
                File(dir, CAPTURE_FILE).readBytes(),
            )
            assertArrayEquals(
                javaClass.getResource("/bundle/golden_calibration.json")!!.readBytes(),
                File(dir, CALIBRATION_FILE).readBytes(),
            )
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a folder killed before finish has no capture json and is not a capture`() {
        val dir = File(tmp, "killed")
        writeCapture(dir)
        // Recording again into the same folder: the old capture.json goes with the first new frame.
        PoseFreeCaptureWriter.writeFrame(dir, 0, goldenFrames()[0])
        PoseFreeCaptureWriter.writeCalibration(dir, goldenCalibration)
        assertFalse(File(dir, CAPTURE_FILE).exists())
        assertFalse(PoseFreeCaptureReader.isPoseFreeCapture(dir))
        assertThrows<CaptureBundleException> { PoseFreeCaptureReader.read(dir) }
    }

    @Test
    fun `a folder with transforms json is not a pose-free capture`() {
        val dir = File(tmp, "posed")
        writeCapture(dir)
        File(dir, "transforms.json").writeText("{}")
        assertFalse(PoseFreeCaptureReader.isPoseFreeCapture(dir))
    }

    @Test
    fun `finish replaces an old capture json`() {
        val dir = File(tmp, "again")
        writeCapture(dir)
        val entry = PoseFreeCaptureWriter.writeFrame(dir, 0, goldenFrames()[1])
        PoseFreeCaptureWriter.finish(dir, listOf(entry), TimestampSource.UNKNOWN)
        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(1, capture.frames.size)
        assertEquals(TimestampSource.UNKNOWN, capture.timestampSource)
        assertFalse(File(dir, "$CAPTURE_FILE.tmp").exists())
    }

    @Test
    fun `version 2 is rejected naming the key`() {
        val dir = File(tmp, "v2")
        writeCapture(dir)
        val file = File(dir, CAPTURE_FILE)
        file.writeText(file.readText().replace("\"splatman_capture_version\": 1", "\"splatman_capture_version\": 2"))
        val e = assertThrows<CaptureBundleException> { PoseFreeCaptureReader.read(dir) }
        assertTrue(e.message!!.contains("splatman_capture_version"), e.message)
        assertTrue(e.message!!.contains("2"), e.message)
    }

    @Test
    fun `a frame whose image is missing is rejected naming the file`() {
        val dir = File(tmp, "missing")
        writeCapture(dir)
        assertTrue(File(dir, "images/frame_00002.jpg").delete())
        val e = assertThrows<CaptureBundleException> { PoseFreeCaptureReader.read(dir) }
        assertTrue(e.message!!.contains("images/frame_00002.jpg"), e.message)
    }

    @Test
    fun `a missing calibration file is rejected naming it`() {
        val dir = File(tmp, "nocal")
        writeCapture(dir)
        assertTrue(File(dir, CALIBRATION_FILE).delete())
        val e = assertThrows<CaptureBundleException> { PoseFreeCaptureReader.read(dir) }
        assertTrue(e.message!!.contains(CALIBRATION_FILE), e.message)
    }

    @Test
    fun `unknown keys are ignored`() {
        val dir = File(tmp, "unknown")
        writeCapture(dir)
        val file = File(dir, CAPTURE_FILE)
        file.writeText(
            file.readText()
                .replaceFirst("{", "{\n    \"future_key\": {\"a\": [1, 2]},")
                .replace("\"sharpness\": 398.1", "\"sharpness\": 398.1, \"iso\": 100"),
        )
        val cal = File(dir, CALIBRATION_FILE)
        cal.writeText(cal.readText().replaceFirst("{", "{\n    \"lens_facing\": \"BACK\","))
        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(2, capture.frames.size)
        assertEquals(398.1f, capture.frames[1].entry.sharpness)
        assertEquals(goldenCalibration, capture.calibration)
    }

    @Test
    fun `NaN sharpness is refused by the writer`() {
        val dir = File(tmp, "nan")
        val frame = goldenFrames()[0].copy(sharpness = Float.NaN)
        assertThrows<IllegalArgumentException> { PoseFreeCaptureWriter.writeFrame(dir, 0, frame) }
        assertFalse(File(dir, "images/frame_00001.jpg").exists())
        val entry = PoseFreeCaptureWriter.writeFrame(dir, 0, goldenFrames()[0]).copy(sharpness = Float.NaN)
        assertThrows<IllegalArgumentException> {
            PoseFreeCaptureWriter.finish(dir, listOf(entry), TimestampSource.REALTIME)
        }
        assertFalse(File(dir, CAPTURE_FILE).exists())
    }

    @Test
    fun `an OBJECT capture json is byte-identical to the golden file and has no mode key`() {
        val dir = File(tmp, "object")
        val entries = goldenFrames().mapIndexed { i, f -> PoseFreeCaptureWriter.writeFrame(dir, i, f) }
        PoseFreeCaptureWriter.writeCalibration(dir, goldenCalibration)
        ImuCsvWriter(File(dir, IMU_FILE)).use { it.gyro(1L, 0.1f, 0.2f, 0.3f) }
        PoseFreeCaptureWriter.finish(dir, entries, TimestampSource.REALTIME, ScanMode.OBJECT)
        val expected = javaClass.getResource("/bundle/golden_capture.json")!!.readBytes()
        assertArrayEquals(expected, File(dir, CAPTURE_FILE).readBytes())
        assertFalse(File(dir, CAPTURE_FILE).readText().contains("\"mode\""))
        assertEquals(ScanMode.OBJECT, PoseFreeCaptureReader.read(dir).mode)
    }

    @Test
    fun `a ROOM capture round-trips its mode`() {
        val dir = File(tmp, "room")
        val entries = goldenFrames().mapIndexed { i, f -> PoseFreeCaptureWriter.writeFrame(dir, i, f) }
        PoseFreeCaptureWriter.writeCalibration(dir, goldenCalibration)
        ImuCsvWriter(File(dir, IMU_FILE)).use { it.gyro(1L, 0.1f, 0.2f, 0.3f) }
        PoseFreeCaptureWriter.finish(dir, entries, TimestampSource.REALTIME, ScanMode.ROOM)
        val text = File(dir, CAPTURE_FILE).readText()
        assertTrue(text.contains("\"mode\": \"room\""), text)
        assertTrue(text.contains("\"splatman_capture_version\": 1"), text)
        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(ScanMode.ROOM, capture.mode)
        assertEquals(entries, capture.frames.map { it.entry })
    }

    @Test
    fun `an unknown or absent capture mode reads as OBJECT`() {
        val dir = File(tmp, "unknownmode")
        writeCapture(dir)
        assertEquals(ScanMode.OBJECT, PoseFreeCaptureReader.read(dir).mode)
        val file = File(dir, CAPTURE_FILE)
        file.writeText(file.readText().replaceFirst("{", "{\n    \"mode\": \"cave\","))
        assertEquals(ScanMode.OBJECT, PoseFreeCaptureReader.read(dir).mode)
    }
}
