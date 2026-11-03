package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CAPTURE_FILE
import com.huntercoles.splatman.splat.io.bundle.CalibrationIntrinsics
import com.huntercoles.splatman.splat.io.bundle.CameraCalibration
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
import com.huntercoles.splatman.splat.io.bundle.IMU_FILE
import com.huntercoles.splatman.splat.io.bundle.ImageSize
import com.huntercoles.splatman.splat.io.bundle.ImuCsvWriter
import com.huntercoles.splatman.splat.io.bundle.IntrinsicsSource
import com.huntercoles.splatman.splat.io.bundle.PoseFreeCaptureWriter
import com.huntercoles.splatman.splat.io.bundle.PoseFreeFrameInput
import com.huntercoles.splatman.splat.io.bundle.TimestampSource
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.Trainer
import com.huntercoles.splatman.splat.train.init.SeedInit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A pose-free capture (capture.json, no transforms.json) waits for camera positions instead of training (#217). */
class PoseFreeProcessingTest {

    @TempDir
    lateinit var scan: File

    private var trained: TrainingInput? = null

    private val trainer = object : Trainer {
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            trained = input
            val cloud = SeedInit.fromSeeds(input.seedPoints)
            emit(TrainingProgress(1, 1, 1f, cloud.count, 1L, cloud))
        }
    }

    private val decoder = KeyframeDecoder { _, longSide ->
        val (w, h) = trainingSize(1280, 720, longSide)
        RgbImage(w, h, ByteArray(w * h * 4) { 0x7f })
    }
    private val thermal = object : ThermalMonitor {
        override val severe = MutableStateFlow(false)
    }

    /** Space is checked after the pose-free check: 0 here would fail a run as STORAGE if it got that far. */
    private var free = Long.MAX_VALUE

    private fun TestScope.repository() = ProcessingRepository(
        trainer, decoder, thermal, TrainingConfig(imageLongSide = 320), { free },
        StandardTestDispatcher(testScheduler), ThumbnailWriter({ _, _, _, out -> out.write(1) }, 0),
    )

    private val calibration = CameraCalibration(
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

    private fun writePoseFree(frames: Int = 3) {
        val entries = List(frames) { i ->
            PoseFreeCaptureWriter.writeFrame(
                scan,
                i,
                PoseFreeFrameInput(1_000L * i, 1920, 1080, 400f, byteArrayOf(1, 2, 3), null, null),
            )
        }
        PoseFreeCaptureWriter.writeCalibration(scan, calibration)
        ImuCsvWriter(File(scan, IMU_FILE)).use { it.gyro(1L, 0.1f, 0.2f, 0.3f) }
        PoseFreeCaptureWriter.finish(scan, entries, TimestampSource.REALTIME)
    }

    private fun writePosedBundle() {
        val camera = CameraIntrinsics(800f, 800f, 639.5f, 359.5f, 1280, 720)
        val pose = CameraPose(Quat.IDENTITY, Vec3(0f, 0f, 0f))
        val frames = List(2) { BundleFrameInput(it * 1_000L, camera, pose, byteArrayOf(1, 2, 3)) }
        val seeds = SeedPoints(1, floatArrayOf(0f, 0f, -1f), byteArrayOf(10, 20, 30))
        CaptureBundleWriter.write(scan, frames, seeds)
    }

    private fun snapshot(): Map<String, List<Byte>> =
        scan.walkTopDown().filter { it.isFile }.associate { it.relativeTo(scan).path to it.readBytes().toList() }

    @Test
    fun `a pose-free capture needs poses and leaves the folder as it was`() = runTest {
        writePoseFree(frames = 3)
        val before = snapshot()
        free = 0

        val result = repository().process(scan)

        assertEquals(ProcessingState.NeedsPoses(3), result)
        assertNull(trained)
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())
        assertFalse(File(scan, ProcessingRepository.TEMP_FILE).exists())
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
        assertEquals(before, snapshot())
    }

    @Test
    fun `after a restart the folder reads back as needs poses`() = runTest {
        writePoseFree(frames = 2)
        repository().process(scan)

        assertEquals(ProcessingState.NeedsPoses(2), repository().state(scan).value)
    }

    @Test
    fun `a pose-free capture never processed reads as needs poses, not idle`() = runTest {
        writePoseFree(frames = 1)

        assertEquals(ProcessingState.NeedsPoses(1), repository().state(scan).value)
    }

    @Test
    fun `capture json beside transforms json trains, the poses were recovered`() = runTest {
        writePosedBundle()
        File(scan, CAPTURE_FILE).writeText("{}")

        val result = repository().process(scan)

        assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
        assertNotNull(trained)
    }

    @Test
    fun `an unreadable capture json fails as FRAMES`() = runTest {
        writePoseFree()
        File(scan, CAPTURE_FILE).writeText("not json")

        val result = repository().process(scan)

        assertEquals(FailureReason.FRAMES, (result as ProcessingState.Failed).reason)
        assertNull(trained)
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())
    }

    @Test
    fun `an unreadable capture json reads as a FRAMES failure after a restart`() = runTest {
        writePoseFree()
        File(scan, CAPTURE_FILE).writeText("not json")

        val state = repository().state(scan).value

        assertTrue(state is ProcessingState.Failed)
        assertEquals(FailureReason.FRAMES, (state as ProcessingState.Failed).reason)
    }
}
