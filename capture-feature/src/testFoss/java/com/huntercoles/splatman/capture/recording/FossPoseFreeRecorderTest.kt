package com.huntercoles.splatman.capture.recording

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.splat.io.bundle.CalibrationIntrinsics
import com.huntercoles.splatman.splat.io.bundle.CameraCalibration
import com.huntercoles.splatman.splat.io.bundle.ImageSize
import com.huntercoles.splatman.splat.io.bundle.ImuCsvReader
import com.huntercoles.splatman.splat.io.bundle.IntrinsicsSource
import com.huntercoles.splatman.splat.io.bundle.PoseFreeCaptureReader
import com.huntercoles.splatman.splat.io.bundle.TimestampSource
import com.huntercoles.splatman.splat.model.RgbImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** [FossPoseFreeRecorder] with a fake frame source, a mocked SensorManager fed by hand and a fake encoder. */
@OptIn(ExperimentalCoroutinesApi::class)
class FossPoseFreeRecorderTest {
    @TempDir lateinit var dir: File

    private val scans get() = File(dir, "scans")
    private val staging get() = File(dir, "recording")

    /** A textured 64x48 image: the same sharpness and mean luma every frame. */
    private class FakeImage : CameraImage {
        override val width = 64
        override val height = 48
        var closes = 0
            private set

        override fun toRgb(): RgbImage {
            check(closes == 0) { "toRgb after close" }
            return RgbImage(width, height, ByteArray(width * height * 4) { 0x80.toByte() })
        }

        override fun luma(): LumaPlane {
            check(closes == 0) { "luma after close" }
            return LumaPlane(width, height, width, ByteArray(width * height) { i -> (((i % width) xor (i / width)) * 37).toByte() })
        }

        override fun close() {
            closes++
        }
    }

    private class FakeEncoder(var fail: Boolean = false) : JpegEncoder {
        var calls = 0
        override fun encode(image: RgbImage, quality: Int): ByteArray {
            if (fail) throw IOException("disk full")
            calls++
            assertEquals(90, quality)
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        }
    }

    private class FakeSource(private val refuse: String? = null) : PoseFreeFrameSource {
        var listener: PoseFreeFrameSource.Listener? = null
        var suspended = false
        var resumes = 0
        var closed = false
        var preview: PreviewTarget? = null

        override suspend fun open(listener: PoseFreeFrameSource.Listener): SourceOpen {
            if (refuse != null) return SourceOpen.Refused(refuse)
            this.listener = listener
            return SourceOpen.Opened(CALIBRATION)
        }

        override suspend fun setPreview(target: PreviewTarget?) {
            preview = target
        }

        override fun suspend() {
            suspended = true
        }

        override suspend fun resume() {
            suspended = false
            resumes++
        }

        override fun close() {
            closed = true
        }
    }

    private val gyro: Sensor = mockk { every { type } returns Sensor.TYPE_GYROSCOPE }
    private val accel: Sensor = mockk { every { type } returns Sensor.TYPE_ACCELEROMETER }
    private val sensors: SensorManager = mockk(relaxed = true) {
        every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyro
        every { getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns accel
        every { registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Handler>()) } returns true
    }
    private val imu = ImuRecorder(sensors, mockk<HandlerThread>(relaxed = true)) { null }
    private val source = FakeSource()
    private val encoder = FakeEncoder()
    private val images = mutableListOf<FakeImage>()
    private var released = 0

    private fun TestScope.recorder(src: FakeSource = source): FossPoseFreeRecorder {
        val d = StandardTestDispatcher(testScheduler)
        return FossPoseFreeRecorder(
            source = src,
            imu = imu,
            scansRoot = scans,
            stagingRoot = staging,
            encoder = encoder,
            worker = d,
            io = d,
            newScanId = { "scan_test" },
            onRelease = { released++ },
        )
    }

    private var clockNs = 1_000_000_000L

    /** 200 ms of gyro at 100 Hz turning [degrees] about device +Y (upright yaw), gravity up, then one frame. */
    private fun turnAndFrame(degrees: Float) {
        val rate = Math.toRadians(degrees / 0.2).toFloat()
        repeat(20) {
            clockNs += 10_000_000L
            imu.onSample(Sensor.TYPE_GYROSCOPE, clockNs, 0f, rate, 0f)
            imu.onSample(Sensor.TYPE_ACCELEROMETER, clockNs, 0f, 9.81f, 0f)
        }
        frame()
    }

    private fun frame(): FakeImage {
        val image = FakeImage().also { images += it }
        checkNotNull(source.listener).onFrame(SourceFrame(clockNs, 8_000_000L, image))
        return image
    }

    private fun TestScope.record(rec: FossPoseFreeRecorder, frames: Int) {
        repeat(frames) {
            turnAndFrame(5f)
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.openAndStart(rec: FossPoseFreeRecorder) {
        assertTrue(rec.open())
        assertEquals(PoseFreeState.Previewing(), rec.state.value)
        advanceUntilIdle()
        assertTrue(rec.start())
    }

    private fun stagingEntries(): List<String> = staging.listFiles()?.map { it.name }.orEmpty()

    private fun scanEntries(): List<String> = scans.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun fiftyFramesTurningFiveDegreesSaveACaptureThatReadsBack() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 50)
        val recording = rec.state.value as PoseFreeState.Recording
        assertEquals(50, recording.keyframes)
        assertTrue(recording.coverage > 0f)
        rec.stop()
        advanceUntilIdle()

        val saved = rec.state.value as PoseFreeState.Saved
        assertEquals("scan_test", saved.scanId)
        val folder = File(scans, saved.scanId)
        assertTrue(File(folder, "imu.csv").isFile)
        assertTrue(File(folder, "calibration.json").isFile)
        assertTrue(File(folder, "capture.json").isFile)
        assertEquals(saved.keyframes, File(folder, "images").listFiles()!!.size)
        val capture = PoseFreeCaptureReader.read(folder)
        assertEquals(saved.keyframes, capture.frames.size)
        assertEquals(50, saved.keyframes)
        assertEquals(CALIBRATION, capture.calibration)
        assertEquals(8_000_000L, capture.frames[0].entry.exposureNs)
        assertEquals(64, capture.frames[0].entry.width)
        assertEquals(2000, ImuCsvReader.read(capture.imuFile).size) // 50 frames x 20 ticks x 2 sensors
        assertEquals(emptyList<String>(), stagingEntries())
        assertTrue(images.all { it.closes >= 1 })
    }

    @Test
    fun cancelLeavesNothingInScansAndEmptiesStaging() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 10)
        assertEquals(listOf("scan_test"), stagingEntries())
        rec.cancel()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), scanEntries())
        assertEquals(emptyList<String>(), stagingEntries())
        assertEquals(PoseFreeState.Previewing(), rec.state.value)
        assertFalse(imu.isRecording)
    }

    @Test
    fun cancelDuringTheSaveKeepsNothing() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 5)
        rec.stop()
        rec.cancel()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), scanEntries())
        assertEquals(emptyList<String>(), stagingEntries())
    }

    @Test
    fun stopWithZeroKeyframesFailsAndLeavesNoFolder() = runTest {
        val rec = recorder()
        openAndStart(rec)
        rec.stop()
        advanceUntilIdle()
        assertEquals(PoseFreeState.Failed("no frames"), rec.state.value)
        assertEquals(emptyList<String>(), scanEntries())
        assertEquals(emptyList<String>(), stagingEntries())
    }

    @Test
    fun cameraRefusedFailsTheOpen() = runTest {
        val refused = FakeSource(refuse = "camera permission denied")
        val rec = recorder(refused)
        assertFalse(rec.open())
        advanceUntilIdle()
        assertEquals(PoseFreeState.Failed("camera permission denied"), rec.state.value)
        assertTrue(refused.closed)
        assertFalse(rec.start())
    }

    @Test
    fun cameraLostMidRecordingKeepsAFinishedCaptureInStaging() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 5)
        checkNotNull(source.listener).onError("camera disconnected")
        advanceUntilIdle()
        assertEquals(PoseFreeState.Failed("camera disconnected"), rec.state.value)
        assertEquals(emptyList<String>(), scanEntries())
        assertEquals(5, PoseFreeCaptureReader.read(File(staging, "scan_test")).frames.size)
        assertTrue(source.closed)
        assertFalse(imu.isRecording)
    }

    @Test
    fun cameraLostBeforeAnyKeyframeDeletesTheStagingFolder() = runTest {
        val rec = recorder()
        openAndStart(rec)
        checkNotNull(source.listener).onError("camera error 4")
        advanceUntilIdle()
        assertEquals(PoseFreeState.Failed("camera error 4"), rec.state.value)
        assertEquals(emptyList<String>(), stagingEntries())
    }

    @Test
    fun aKeyframeWriteFailureFailsAndDropsAnUnfinishableFolder() = runTest {
        val rec = recorder()
        openAndStart(rec)
        encoder.fail = true
        turnAndFrame(5f)
        advanceUntilIdle()
        assertTrue((rec.state.value as PoseFreeState.Failed).reason.contains("disk full"))
        assertEquals(emptyList<String>(), stagingEntries())
    }

    @Test
    fun backgroundPausesFramesAndSensorsAndForegroundResumes() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 2)
        assertTrue(imu.isRecording)
        rec.setForeground(false)
        assertTrue(source.suspended)
        assertFalse(imu.isRecording)
        val inBackground = frame()
        advanceUntilIdle()
        assertEquals(1, inBackground.closes)
        assertEquals(2, (rec.state.value as PoseFreeState.Recording).keyframes)

        rec.setForeground(true)
        advanceUntilIdle()
        assertFalse(source.suspended)
        assertEquals(1, source.resumes)
        assertTrue(imu.isRecording)
        record(rec, 1)
        assertEquals(3, (rec.state.value as PoseFreeState.Recording).keyframes)
    }

    @Test
    fun pauseKeepsNoKeyframes() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 2)
        rec.pause(true)
        record(rec, 3)
        assertEquals(2, (rec.state.value as PoseFreeState.Recording).keyframes)
        rec.pause(false)
        record(rec, 1)
        assertEquals(3, (rec.state.value as PoseFreeState.Recording).keyframes)
    }

    @Test
    fun releaseWhileSavingLetsTheSaveFinish() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 3)
        rec.stop()
        rec.release()
        advanceUntilIdle()
        assertEquals(PoseFreeState.Saved("scan_test", 3), rec.state.value)
        assertTrue(PoseFreeCaptureReader.isPoseFreeCapture(File(scans, "scan_test")))
        assertTrue(source.closed)
        assertEquals(1, released)
    }

    @Test
    fun releaseWhileRecordingKeepsNothing() = runTest {
        val rec = recorder()
        openAndStart(rec)
        record(rec, 3)
        rec.release()
        advanceUntilIdle()
        assertEquals(PoseFreeState.Idle, rec.state.value)
        assertEquals(emptyList<String>(), stagingEntries())
        assertEquals(emptyList<String>(), scanEntries())
    }

    @Test
    fun aBusyWorkerDropsNewFramesInsteadOfQueueingThem() = runTest {
        val rec = recorder()
        openAndStart(rec)
        turnAndFrame(5f) // handed to the worker, which has not run yet
        val second = frame() // waits in the one slot
        assertEquals(0, second.closes)
        val third = frame() // no room: closed at once, never queued
        assertEquals(1, third.closes)
        val fourth = frame()
        assertEquals(1, fourth.closes)
        advanceUntilIdle()
        val s = rec.state.value as PoseFreeState.Recording
        assertEquals(1, s.keyframes) // the second came at the same instant: too soon for a keyframe
        assertEquals(2, s.droppedFrames)
        assertTrue(images.all { it.closes >= 1 })
    }

    @Test
    fun previewIsHandedToTheSource() = runTest {
        val rec = recorder()
        openAndStart(rec)
        val target = mockk<PreviewTarget>()
        rec.setPreview(target)
        advanceUntilIdle()
        assertEquals(target, source.preview)
        rec.setPreview(null)
        advanceUntilIdle()
        assertNull(source.preview)
    }

    @Test
    fun streamSizeIs1080pElse720p() {
        val hd = ImageSize(1280, 720)
        val fullHd = ImageSize(1920, 1080)
        assertEquals(fullHd, Camera2FrameSource.chooseStreamSize(listOf(ImageSize(4000, 3000), hd, fullHd)))
        assertEquals(hd, Camera2FrameSource.chooseStreamSize(listOf(ImageSize(4000, 3000), hd, ImageSize(640, 480))))
        assertNull(Camera2FrameSource.chooseStreamSize(listOf(ImageSize(640, 480))))
    }

    @Test
    fun fpsRangeHolds30Steadiest() {
        assertEquals(30 to 30, Camera2FrameSource.chooseFpsRange(listOf(15 to 30, 30 to 30, 7 to 60)))
        assertEquals(15 to 30, Camera2FrameSource.chooseFpsRange(listOf(7 to 30, 15 to 30, 24 to 24)))
        assertEquals(24 to 60, Camera2FrameSource.chooseFpsRange(listOf(7 to 60, 24 to 60, 15 to 24)))
        assertNull(Camera2FrameSource.chooseFpsRange(listOf(15 to 24)))
    }

    private companion object {
        val CALIBRATION = CameraCalibration(
            cameraId = "0",
            sensorOrientationDeg = 90,
            timestampSource = TimestampSource.REALTIME,
            pixelArraySize = listOf(4000, 3000),
            activeArray = null,
            preCorrectionActiveArray = null,
            physicalSizeMm = listOf(6.4f, 4.8f),
            focalLengthsMm = listOf(4.2f),
            lensIntrinsicCalibration = null,
            lensDistortion = null,
            lensPoseRotation = null,
            lensPoseTranslation = null,
            lensPoseReference = null,
            image = ImageSize(64, 48),
            intrinsics = CalibrationIntrinsics(50f, 50f, 32f, 24f, IntrinsicsSource.FOCAL_LENGTH),
        )
    }
}
