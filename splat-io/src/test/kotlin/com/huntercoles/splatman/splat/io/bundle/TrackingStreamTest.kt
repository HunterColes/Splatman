package com.huntercoles.splatman.splat.io.bundle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class TrackingStreamTest {

    @TempDir
    lateinit var tmp: File

    private val calibration = CameraCalibration(
        cameraId = "0",
        sensorOrientationDeg = 90,
        timestampSource = TimestampSource.REALTIME,
        pixelArraySize = null,
        activeArray = null,
        preCorrectionActiveArray = null,
        physicalSizeMm = null,
        focalLengthsMm = null,
        lensIntrinsicCalibration = null,
        lensDistortion = null,
        lensPoseRotation = null,
        lensPoseTranslation = null,
        lensPoseReference = null,
        image = ImageSize(4, 2),
        intrinsics = CalibrationIntrinsics(4f, 4f, 2f, 1f, IntrinsicsSource.FOCAL_LENGTH),
    )

    private fun luma(seed: Int, size: Int) = ByteArray(size) { (seed * 31 + it).toByte() }

    /** Three 4x3 frames: every value, then every value null, then a mix. */
    private fun writeStream(dir: File): TrackingStreamInfo =
        TrackingStreamWriter(dir, 4, 3, 2).use { w ->
            w.write(100L, 8_333_333L, 21_000_000L, 2.5f, luma(0, 12))
            w.write(200L, null, null, null, luma(1, 12))
            w.write(300L, 1_000L, null, 0.0001f, luma(2, 12))
            assertEquals(3, w.frameCount)
            w.info
        }

    /** A one-frame capture whose frame carries [skew] and [focus], with [stream] listed. */
    private fun writeCapture(
        dir: File,
        skew: Long? = 33_000_000L,
        focus: Float? = 1.25f,
        stream: TrackingStreamInfo? = null,
        cal: CameraCalibration = calibration,
    ): List<FrameEntry> {
        val input = PoseFreeFrameInput(10L, 4, 2, 1f, byteArrayOf(9), null, 5L, skew, focus)
        val entries = listOf(PoseFreeCaptureWriter.writeFrame(dir, 0, input))
        PoseFreeCaptureWriter.writeCalibration(dir, cal)
        ImuCsvWriter(File(dir, IMU_FILE)).use { it.gyro(1L, 0f, 0f, 0f) }
        PoseFreeCaptureWriter.finish(dir, entries, TimestampSource.REALTIME, stream = stream)
        return entries
    }

    @Test
    fun `stream round-trips entries and frames, nulls as empty cells`() {
        val dir = File(tmp, "s")
        val info = writeStream(dir)
        assertEquals(TrackingStreamInfo(STREAM_Y8_FILE, STREAM_CSV_FILE, 4, 3, 2), info)
        assertEquals(36L, File(dir, STREAM_Y8_FILE).length())
        assertEquals(
            listOf(STREAM_HEADER, "0,100,8333333,21000000,2.5", "1,200,,,", "2,300,1000,,1.0E-4"),
            File(dir, STREAM_CSV_FILE).readLines(),
        )
        TrackingStreamReader(dir, info).use { r ->
            assertEquals(3, r.size)
            assertEquals(StreamEntry(0, 100L, 8_333_333L, 21_000_000L, 2.5f), r.entries[0])
            assertEquals(StreamEntry(1, 200L, null, null, null), r.entries[1])
            assertEquals(StreamEntry(2, 300L, 1_000L, null, 0.0001f), r.entries[2])
            assertArrayEquals(luma(2, 12), r.frame(2))
            assertArrayEquals(luma(0, 12), r.frame(0))
            assertArrayEquals(luma(1, 12), r.frame(1))
            assertThrows<IndexOutOfBoundsException> { r.frame(3) }
        }
    }

    @Test
    fun `stream csv is the same under a comma-decimal default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val dir = File(tmp, "de")
            writeStream(dir)
            assertEquals("0,100,8333333,21000000,2.5", File(dir, STREAM_CSV_FILE).readLines()[1])
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a wrong-size frame throws and writes nothing`() {
        val dir = File(tmp, "wrong")
        TrackingStreamWriter(dir, 4, 3, 1).use { w ->
            assertThrows<IllegalArgumentException> { w.write(1L, null, null, null, ByteArray(11)) }
            assertThrows<IllegalArgumentException> { w.write(1L, null, null, null, ByteArray(13)) }
            assertThrows<IllegalArgumentException> { w.write(1L, null, null, Float.NaN, ByteArray(12)) }
            assertEquals(0, w.frameCount)
        }
        assertEquals(0L, File(dir, STREAM_Y8_FILE).length())
        assertEquals(listOf(STREAM_HEADER), File(dir, STREAM_CSV_FILE).readLines())
    }

    @Test
    fun `writing after close throws`() {
        val w = TrackingStreamWriter(File(tmp, "closed"), 1, 1, 1)
        w.close()
        assertThrows<IllegalStateException> { w.write(1L, null, null, null, ByteArray(1)) }
    }

    @Test
    fun `reader rejects a truncated y8, a missing file and a bad row`() {
        val dir = File(tmp, "bad")
        val info = writeStream(dir)
        RandomAccessFile(File(dir, STREAM_Y8_FILE), "rw").use { it.setLength(35) }
        assertThrows<CaptureBundleException> { TrackingStreamReader(dir, info) }
        File(dir, STREAM_Y8_FILE).delete()
        assertThrows<CaptureBundleException> { TrackingStreamReader(dir, info) }

        val rows = File(tmp, "rows")
        val info2 = writeStream(rows)
        val csv = File(rows, STREAM_CSV_FILE)
        csv.writeText(csv.readText().replace("1,200,,,", "1,200,x,,"))
        assertThrows<CaptureBundleException> { TrackingStreamReader(rows, info2) }
        csv.writeText("$STREAM_HEADER\n0,1,,,\n2,2,,,\n")
        assertThrows<CaptureBundleException> { TrackingStreamReader(rows, info2) }
    }

    @Test
    fun `capture round-trips skew, focus, focus calibration and stream`() {
        val dir = File(tmp, "cap")
        val info = writeStream(dir)
        val cal = calibration.copy(focusDistanceCalibration = "APPROXIMATE")
        val entries = writeCapture(dir, stream = info, cal = cal)
        val text = File(dir, CAPTURE_FILE).readText()
        assertTrue(text.contains("\"rolling_shutter_skew_ns\": 33000000"), text)
        assertTrue(text.contains("\"focus_distance_dpt\": 1.25"), text)
        assertTrue(text.contains("\"stream\""), text)
        assertTrue(text.contains("\"index_path\": \"stream.csv\""), text)
        assertTrue(text.contains("\"splatman_capture_version\": 1"), text)
        assertTrue(File(dir, CALIBRATION_FILE).readText().contains("\"focus_distance_calibration\": \"APPROXIMATE\""))

        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(entries, capture.frames.map { it.entry })
        assertEquals(33_000_000L, capture.frames[0].entry.rollingShutterSkewNs)
        assertEquals(1.25f, capture.frames[0].entry.focusDistanceDpt)
        assertEquals(cal, capture.calibration)
        assertEquals(info, capture.stream)
        assertNull(capture.streamProblem)
    }

    @Test
    fun `null keys are absent from the json text`() {
        val dir = File(tmp, "nulls")
        writeCapture(dir, skew = null, focus = null, stream = null)
        val text = File(dir, CAPTURE_FILE).readText()
        assertFalse(text.contains("rolling_shutter_skew_ns"), text)
        assertFalse(text.contains("focus_distance_dpt"), text)
        assertFalse(text.contains("stream"), text)
        assertFalse(File(dir, CALIBRATION_FILE).readText().contains("focus_distance_calibration"))
        val capture = PoseFreeCaptureReader.read(dir)
        assertNull(capture.frames[0].entry.rollingShutterSkewNs)
        assertNull(capture.frames[0].entry.focusDistanceDpt)
        assertNull(capture.calibration.focusDistanceCalibration)
        assertNull(capture.stream)
        assertNull(capture.streamProblem)
    }

    @Test
    fun `a truncated stream y8 gives stream null and the capture still reads`() {
        val dir = File(tmp, "trunc")
        val info = writeStream(dir)
        writeCapture(dir, stream = info)
        RandomAccessFile(File(dir, STREAM_Y8_FILE), "rw").use { it.setLength(24) }
        val capture = PoseFreeCaptureReader.read(dir)
        assertNull(capture.stream)
        assertNotNull(capture.streamProblem)
        assertEquals(1, capture.frames.size)
    }

    @Test
    fun `a missing stream file gives stream null and the capture still reads`() {
        val dir = File(tmp, "missing")
        val info = writeStream(dir)
        writeCapture(dir, stream = info)
        File(dir, STREAM_CSV_FILE).delete()
        val capture = PoseFreeCaptureReader.read(dir)
        assertNull(capture.stream)
        assertTrue(capture.streamProblem!!.contains(STREAM_CSV_FILE))
    }

    @Test
    fun `a capture json and calibration json from master today read unchanged`() {
        val dir = File(tmp, "old")
        File(dir, "images").mkdirs()
        File(dir, "images/frame_00001.jpg").writeBytes(byteArrayOf(1))
        File(dir, "images/frame_00002.jpg").writeBytes(byteArrayOf(2))
        File(dir, IMU_FILE).writeText("$IMU_HEADER\n")
        File(dir, CAPTURE_FILE).writeBytes(javaClass.getResource("/bundle/golden_capture.json")!!.readBytes())
        File(dir, CALIBRATION_FILE).writeBytes(javaClass.getResource("/bundle/golden_calibration.json")!!.readBytes())
        val capture = PoseFreeCaptureReader.read(dir)
        assertEquals(SPLATMAN_CAPTURE_VERSION, capture.version)
        assertEquals(2, capture.frames.size)
        assertEquals(8_333_333L, capture.frames[0].entry.exposureNs)
        capture.frames.forEach {
            assertNull(it.entry.rollingShutterSkewNs)
            assertNull(it.entry.focusDistanceDpt)
        }
        assertNull(capture.calibration.focusDistanceCalibration)
        assertNull(capture.stream)
        assertNull(capture.streamProblem)
    }
}
