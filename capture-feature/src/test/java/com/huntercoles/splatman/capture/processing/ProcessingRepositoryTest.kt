package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.io.bundle.BundleFrameInput
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleWriter
import com.huntercoles.splatman.splat.io.ply.PlyWriter
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class ProcessingRepositoryTest {

    @TempDir
    lateinit var scan: File

    private val seeds = SeedPoints(
        4,
        floatArrayOf(0f, 0f, -1f, 0.1f, 0f, -1f, 0f, 0.1f, -1f, 0f, 0f, -1.1f),
        byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
    )
    private val camera = CameraIntrinsics(800f, 800f, 639.5f, 359.5f, 1280, 720)

    /** Fake decoder: a grey image at the training size of the bundle's 1280 x 720 frames; records the long side. */
    private val decodedLongSides = mutableListOf<Int>()
    private val decoder = KeyframeDecoder { _, longSide ->
        decodedLongSides += longSide
        val (w, h) = trainingSize(1280, 720, longSide)
        RgbImage(w, h, ByteArray(w * h * 4) { 0x7f })
    }
    private val thermal = object : ThermalMonitor {
        override val severe = MutableStateFlow(false)
    }

    private var free = Long.MAX_VALUE
    private val freeSpace = FreeSpace { free }

    /** Fake trainer: three progress steps, the last carrying the seed splat; records its input. */
    private class StepTrainer : Trainer {
        var input: TrainingInput? = null
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            this@StepTrainer.input = input
            val cloud = SeedInit.fromSeeds(input.seedPoints)
            for (i in 1..3) emit(TrainingProgress(i, 3, 1f / i, cloud.count, i.toLong(), if (i == 3) cloud else null))
        }
    }

    private fun writeBundle() {
        val pose = CameraPose(Quat.IDENTITY, Vec3(0f, 0f, 0f))
        val frames = List(2) { BundleFrameInput(it * 1_000L, camera, pose, byteArrayOf(1, 2, 3)) }
        CaptureBundleWriter.write(scan, frames, seeds)
    }

    private fun bundleSnapshot(): Map<String, List<Byte>> =
        scan.walkTopDown().filter { it.isFile }.associate { it.relativeTo(scan).path to it.readBytes().toList() }

    /** Fake PNG encoder: writes a marker and the pixel count; records the size it was given. */
    private val encodedSizes = mutableListOf<Pair<Int, Int>>()
    private val fakeEncoder = ThumbnailEncoder { argb, width, height, out ->
        encodedSizes += width to height
        out.write("PNG ${argb.size}".toByteArray())
    }

    private fun TestScope.repository(
        trainer: Trainer,
        config: TrainingConfig = TrainingConfig(imageLongSide = 320),
        encoder: ThumbnailEncoder = fakeEncoder,
    ) = ProcessingRepository(
        trainer, decoder, thermal, config, freeSpace, StandardTestDispatcher(testScheduler),
        ThumbnailWriter(encoder, BACKGROUND),
    )

    @Test
    fun `a finished run leaves splat ply and thumbnail png`() = runTest {
        writeBundle()
        val result = repository(StepTrainer()).process(scan)

        assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
        assertTrue(File(scan, ProcessingRepository.SPLAT_FILE).isFile)
        assertEquals("PNG ${256 * 320}", File(scan, ThumbnailWriter.FILE).readText())
        assertEquals(listOf(256 to 320), encodedSizes)
        assertFalse(File(scan, ThumbnailWriter.TEMP_FILE).exists())
    }

    @Test
    fun `a thumbnail encoder that throws still ends Done with the splat and no temp file`() = runTest {
        writeBundle()
        val throwing = ThumbnailEncoder { _, _, _, out ->
            out.write(byteArrayOf(1, 2, 3))
            out.flush()
            throw IllegalStateException("encoder broke")
        }
        val result = repository(StepTrainer(), encoder = throwing).process(scan)

        assertEquals(ProcessingState.Done(File(scan, ProcessingRepository.SPLAT_FILE)), result)
        assertTrue(File(scan, ProcessingRepository.SPLAT_FILE).isFile)
        assertFalse(File(scan, ThumbnailWriter.TEMP_FILE).exists())
        assertFalse(File(scan, ThumbnailWriter.FILE).exists())
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
    }

    @Test
    fun `forwards progress, downscales keyframes and writes splat ply`() = runTest {
        writeBundle()
        val trainer = StepTrainer()
        val repo = repository(trainer)
        val seen = mutableListOf<TrainingProgress>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { repo.progress(scan).collect { seen += it } }

        val result = repo.process(scan)
        runCurrent()
        watcher.cancel()

        val out = File(scan, ProcessingRepository.SPLAT_FILE)
        assertEquals(ProcessingState.Done(out), result)
        assertEquals(result, repo.state(scan).value)
        assertEquals(listOf(1, 2, 3), seen.map { it.iteration })
        assertEquals(listOf(320, 320), decodedLongSides)
        val keyframes = trainer.input!!.keyframes
        assertEquals(2, keyframes.size)
        assertEquals(320, keyframes[0].intrinsics.width)
        assertEquals(180, keyframes[0].intrinsics.height)
        assertEquals(200f, keyframes[0].intrinsics.fx, 1e-4f)
        assertEquals(4, trainer.input!!.seedPoints.count)
        val expected = ByteArrayOutputStream().also { PlyWriter.write(SeedInit.fromSeeds(seeds), it) }.toByteArray()
        assertArrayEquals(expected, out.readBytes())
        assertFalse(File(scan, ProcessingRepository.TEMP_FILE).exists())
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
    }

    private val hangingSeedTrainer = object : Trainer {
        val started = MutableStateFlow(false)
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            emit(TrainingProgress(0, 10, Float.NaN, 4, 1L, SeedInit.fromSeeds(input.seedPoints)))
            emit(TrainingProgress(1, 10, 0.5f, 4, 2L, null))
            started.value = true
            awaitCancellation()
        }
    }

    @Test
    fun `cancel leaves the bundle intact and the seed splat as the result`() = runTest {
        writeBundle()
        val before = bundleSnapshot()
        val repo = repository(hangingSeedTrainer)
        val job = launch { repo.process(scan) }
        runCurrent()
        assertTrue(hangingSeedTrainer.started.value)
        assertTrue(repo.isRunning(scan))

        repo.cancel(scan)
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(ProcessingState.Cancelled, repo.state(scan).value)
        val out = File(scan, ProcessingRepository.SPLAT_FILE)
        val expected = ByteArrayOutputStream().also { PlyWriter.write(SeedInit.fromSeeds(seeds), it) }.toByteArray()
        assertArrayEquals(expected, out.readBytes())
        assertFalse(File(scan, ProcessingRepository.TEMP_FILE).exists())
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
        assertFalse(repo.isRunning(scan))
        assertEquals(before + (ProcessingRepository.SPLAT_FILE to expected.toList()), bundleSnapshot())
    }

    @Test
    fun `cancel never replaces an earlier splat ply`() = runTest {
        writeBundle()
        val out = File(scan, ProcessingRepository.SPLAT_FILE)
        out.writeBytes(byteArrayOf(7, 7, 7))
        val repo = repository(hangingSeedTrainer)
        launch { repo.process(scan) }
        runCurrent()

        repo.cancel(scan)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(7, 7, 7), out.readBytes())
        assertEquals(ProcessingState.Cancelled, repo.state(scan).value)
    }

    @Test
    fun `a trainer error becomes a failed state`() = runTest {
        writeBundle()
        val before = bundleSnapshot()
        val trainer = object : Trainer {
            override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
                emit(TrainingProgress(1, 10, 0.5f, 4, 1L))
                error("diverged at 1")
            }
        }
        val repo = repository(trainer)

        val result = repo.process(scan)

        assertEquals(ProcessingState.Failed("diverged at 1"), result)
        assertEquals(result, repo.state(scan).value)
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())
        assertEquals(before, bundleSnapshot())
    }

    @Test
    fun `a missing bundle is a failed state`() = runTest {
        val result = repository(StepTrainer()).process(scan)

        assertTrue(result is ProcessingState.Failed)
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())
    }

    @Test
    fun `pauses while the phone is severe and resumes when it cools`() = runTest {
        writeBundle()
        thermal.severe.value = true
        val repo = repository(StepTrainer())
        val job = launch { repo.process(scan) }
        runCurrent()

        val paused = repo.state(scan).value
        assertTrue(paused is ProcessingState.Paused, "was $paused")
        assertEquals(1, (paused as ProcessingState.Paused).progress.iteration)
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())

        thermal.severe.value = false
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(repo.state(scan).value is ProcessingState.Done)
    }

    @Test
    fun `a run killed mid-way reads back as failed and restarts cleanly`() = runTest {
        writeBundle()
        // What process death leaves: the marker and a half-written temp file, no splat.ply.
        File(scan, ProcessingRepository.RUNNING_MARKER).writeText("")
        File(scan, ProcessingRepository.TEMP_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val repo = repository(StepTrainer())

        assertEquals(ProcessingState.Failed(ProcessingRepository.INTERRUPTED), repo.state(scan).value)
        assertFalse(File(scan, ProcessingRepository.SPLAT_FILE).exists())

        val result = repo.process(scan)

        assertTrue(result is ProcessingState.Done)
        assertFalse(File(scan, ProcessingRepository.TEMP_FILE).exists())
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
    }

    @Test
    fun `a failed rerun keeps the earlier splat ply`() = runTest {
        writeBundle()
        repository(StepTrainer()).process(scan)
        val earlier = File(scan, ProcessingRepository.SPLAT_FILE).readBytes()
        val failing = object : Trainer {
            override fun train(input: TrainingInput): Flow<TrainingProgress> = flow { error("boom") }
        }

        repository(failing).process(scan)

        assertArrayEquals(earlier, File(scan, ProcessingRepository.SPLAT_FILE).readBytes())
    }

    @Test
    fun `low storage before the start fails with the storage reason and the shortfall`() = runTest {
        writeBundle()
        free = MIN_FREE_BYTES - 1000
        val trainer = StepTrainer()

        val result = repository(trainer).process(scan)

        assertTrue(result is ProcessingState.Failed, "was $result")
        assertEquals(FailureReason.STORAGE, (result as ProcessingState.Failed).reason)
        assertEquals(1000L, result.needBytes)
        assertEquals(null, trainer.input)
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())

        free = Long.MAX_VALUE
        assertTrue(repository(trainer).process(scan) is ProcessingState.Done)
    }

    @Test
    fun `running out of memory fails with the memory reason`() = runTest {
        writeBundle()
        val trainer = object : Trainer {
            override fun train(input: TrainingInput): Flow<TrainingProgress> = flow { throw OutOfMemoryError("heap") }
        }

        val result = repository(trainer).process(scan)

        assertEquals(FailureReason.MEMORY, (result as ProcessingState.Failed).reason)
        assertFalse(File(scan, ProcessingRepository.RUNNING_MARKER).exists())
    }

    @Test
    fun `reports frame loading, then training, then saving`() = runTest {
        writeBundle()
        val repo = repository(StepTrainer())
        val seen = mutableListOf<ProcessingState>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { repo.state(scan).collect { seen += it } }

        repo.process(scan)
        runCurrent()
        watcher.cancel()

        val running = seen.filterIsInstance<ProcessingState.Running>()
        assertEquals(listOf(0f, 0.5f, 1f), running.filter { it.progress == null }.map { it.prepared })
        assertEquals(listOf(1, 2, 3), running.filter { !it.saving }.mapNotNull { it.progress?.iteration })
        assertTrue(running.last().saving)
        assertTrue(seen.last() is ProcessingState.Done)
    }

    private companion object {
        const val BACKGROUND = 0xFF0A0A0D.toInt()
    }
}
