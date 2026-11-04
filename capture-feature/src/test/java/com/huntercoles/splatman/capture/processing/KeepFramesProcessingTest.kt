package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** Settings > Keep capture frames (#443): off, a finished build deletes the scan's `images/`; else they stay. */
class KeepFramesProcessingTest {

    @TempDir
    lateinit var root: File

    private val scan by lazy { File(root, "scan").apply { mkdirs() } }
    private val images get() = File(scan, CaptureFrames.DIR)

    private val seeds = SeedPoints(
        4,
        floatArrayOf(0f, 0f, -1f, 0.1f, 0f, -1f, 0f, 0.1f, -1f, 0f, 0f, -1.1f),
        byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
    )
    private val camera = CameraIntrinsics(800f, 800f, 639.5f, 359.5f, 1280, 720)
    private val decoder = KeyframeDecoder { _, longSide ->
        val (w, h) = trainingSize(1280, 720, longSide)
        RgbImage(w, h, ByteArray(w * h * 4) { 0x7f })
    }
    private val thermal = object : ThermalMonitor {
        override val severe = MutableStateFlow(false)
    }
    private val encoder = ThumbnailEncoder { argb, _, _, out -> out.write("PNG ${argb.size}".toByteArray()) }

    /** The setting as the run sees it; [reads] counts how often it was asked. */
    private var keep = false
    private var reads = 0
    private val keepFrames = KeepFrames {
        reads++
        keep
    }

    private class StepTrainer(private val onStep: (Int) -> Unit = {}) : Trainer {
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            val cloud = SeedInit.fromSeeds(input.seedPoints)
            for (i in 1..3) {
                onStep(i)
                emit(TrainingProgress(i, 3, 1f / i, cloud.count, i.toLong(), if (i == 3) cloud else null))
            }
        }
    }

    private fun writeBundle() {
        val pose = CameraPose(Quat.IDENTITY, Vec3(0f, 0f, 0f))
        val frames = List(2) { BundleFrameInput(it * 1_000L, camera, pose, byteArrayOf(1, 2, 3)) }
        CaptureBundleWriter.write(scan, frames, seeds)
    }

    private fun TestScope.repository(trainer: Trainer, frames: KeepFrames = keepFrames) = ProcessingRepository(
        trainer, decoder, thermal, TrainingConfig(imageLongSide = 320), FreeSpace { Long.MAX_VALUE },
        StandardTestDispatcher(testScheduler), ThumbnailWriter(encoder, 0), frames,
    )

    private fun imageFiles(): List<String> = images.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `keep frames off deletes images after Done and keeps the rest of the scan`() = runTest {
        writeBundle()
        assertEquals(2, imageFiles().size)

        val result = repository(StepTrainer()).process(scan)

        assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
        assertFalse(images.exists())
        assertTrue(File(scan, "transforms.json").isFile)
        assertTrue(File(scan, ProcessingRepository.SPLAT_FILE).isFile)
        assertTrue(File(scan, ThumbnailWriter.FILE).isFile)
        assertTrue(scan.isDirectory)
    }

    @Test
    fun `keep frames on leaves images untouched after Done`() = runTest {
        keep = true
        writeBundle()
        val before = images.listFiles()!!.associate { it.name to it.readBytes().toList() }

        val result = repository(StepTrainer()).process(scan)

        assertTrue(result is ProcessingState.Done)
        assertEquals(before, images.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test
    fun `the default repository keeps frames`() = runTest {
        writeBundle()
        val repo = ProcessingRepository(
            StepTrainer(), decoder, thermal, TrainingConfig(imageLongSide = 320), FreeSpace { Long.MAX_VALUE },
            StandardTestDispatcher(testScheduler), ThumbnailWriter(encoder, 0),
        )

        assertTrue(repo.process(scan) is ProcessingState.Done)
        assertEquals(2, imageFiles().size)
    }

    @Test
    fun `a failed run keeps images with keep frames off`() = runTest {
        writeBundle()
        val trainer = object : Trainer {
            override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
                emit(TrainingProgress(1, 10, 0.5f, 4, 1L))
                error("diverged")
            }
        }

        val result = repository(trainer).process(scan)

        assertEquals(ProcessingState.Failed("diverged"), result)
        assertEquals(2, imageFiles().size)
        assertEquals(0, reads)
    }

    @Test
    fun `a cancelled run keeps images with keep frames off`() = runTest {
        writeBundle()
        val started = MutableStateFlow(false)
        val trainer = object : Trainer {
            override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
                emit(TrainingProgress(1, 10, 0.5f, 4, 1L, SeedInit.fromSeeds(input.seedPoints)))
                started.value = true
                awaitCancellation()
            }
        }
        val repo = repository(trainer)
        launch { repo.process(scan) }
        runCurrent()
        assertTrue(started.value)

        repo.cancel(scan)
        advanceUntilIdle()

        assertEquals(ProcessingState.Cancelled, repo.state(scan).value)
        assertTrue(File(scan, ProcessingRepository.SPLAT_FILE).isFile)
        assertEquals(2, imageFiles().size)
        assertEquals(0, reads)
    }

    @Test
    fun `an interrupted run reads back as failed and keeps images`() = runTest {
        writeBundle()
        File(scan, ProcessingRepository.RUNNING_MARKER).writeText("")

        val state = repository(StepTrainer()).state(scan).value

        assertEquals(ProcessingState.Failed(ProcessingRepository.INTERRUPTED), state)
        assertEquals(2, imageFiles().size)
        assertEquals(0, reads)
    }

    @Test
    fun `the setting is read when the run finishes, not when it starts`() = runTest {
        keep = true
        writeBundle()
        val trainer = StepTrainer { step -> if (step == 3) keep = false }

        val result = repository(trainer).process(scan)

        assertTrue(result is ProcessingState.Done)
        assertFalse(images.exists())
        assertEquals(1, reads)
    }

    @Test
    fun `a delete failure is logged and the run is still Done`() = runTest {
        writeBundle()
        assertTrue(images.setWritable(false))
        try {
            val result = repository(StepTrainer()).process(scan)

            assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
            assertTrue(File(scan, ProcessingRepository.SPLAT_FILE).isFile)
        } finally {
            images.setWritable(true)
        }
    }

    @Test
    fun `a setting that throws keeps the frames and the run is still Done`() = runTest {
        writeBundle()
        val result = repository(StepTrainer(), KeepFrames { error("prefs broke") }).process(scan)

        assertTrue(result is ProcessingState.Done)
        assertEquals(2, imageFiles().size)
    }

    @Test
    fun `a scan with no images folder at the end finishes Done without error`() = runTest {
        writeBundle()
        // The bundle reader needs the frames to start, so they go once loaded: none are left when the run ends.
        val trainer = StepTrainer { step -> if (step == 1) assertTrue(images.deleteRecursively()) }

        val result = repository(trainer).process(scan)

        assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
        assertFalse(images.exists())
        assertEquals(1, reads)
    }

    @Test
    fun `deleting the frames of a scan with no images folder succeeds and touches nothing`() {
        File(scan, ProcessingRepository.SPLAT_FILE).writeText("ply")

        assertTrue(CaptureFrames.delete(scan))

        assertEquals(listOf(ProcessingRepository.SPLAT_FILE), scan.list()!!.toList())
    }

    @Test
    fun `a symlink inside images is removed and never followed out of the scan`() = runTest {
        writeBundle()
        val outside = File(root, "outside").apply { mkdirs() }
        val kept = File(outside, "precious.jpg").apply { writeText("keep me") }
        val sibling = File(root, "other-scan/images/frame_00001.jpg").apply {
            parentFile!!.mkdirs()
            writeText("other")
        }
        Files.createSymbolicLink(File(images, "dir-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(images, "file-link.jpg").toPath(), kept.toPath())

        val result = repository(StepTrainer()).process(scan)

        assertTrue(result is ProcessingState.Done)
        assertFalse(images.exists())
        assertEquals("keep me", kept.readText())
        assertEquals(listOf("precious.jpg"), outside.list()!!.toList())
        assertEquals("other", sibling.readText())
    }

    @Test
    fun `an images symlink is removed as a link, its target kept`() = runTest {
        writeBundle()
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "precious.jpg").writeText("keep me")
        images.deleteRecursively()
        Files.createSymbolicLink(images.toPath(), outside.toPath())

        assertTrue(CaptureFrames.delete(scan))

        assertFalse(Files.exists(images.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep me", File(outside, "precious.jpg").readText())
    }
}
