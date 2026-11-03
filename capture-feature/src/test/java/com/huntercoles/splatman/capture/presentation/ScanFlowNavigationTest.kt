package com.huntercoles.splatman.capture.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.capture.presentation.capture.CaptureIntent
import com.huntercoles.splatman.capture.presentation.capture.CaptureTracker
import com.huntercoles.splatman.capture.presentation.capture.CaptureViewModel
import com.huntercoles.splatman.capture.presentation.processing.ProcessingEvent
import com.huntercoles.splatman.capture.presentation.processing.ProcessingIntent
import com.huntercoles.splatman.capture.presentation.processing.ProcessingStage
import com.huntercoles.splatman.capture.presentation.processing.ProcessingViewModel
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.processing.FreeSpace
import com.huntercoles.splatman.capture.processing.KeyframeDecoder
import com.huntercoles.splatman.capture.processing.NotificationAsk
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingStarter
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanDeleter
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.capture.processing.ThermalMonitor
import com.huntercoles.splatman.capture.processing.ThumbnailWriter
import com.huntercoles.splatman.capture.processing.trainingSize
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.JpegEncoder
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.LumaPlane
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.ScanFlow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.viewer.export.ExportTarget
import java.io.IOException
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scan flow at view-model level: Capture saves a scan and names it, Processing builds its splat and names it,
 * and the shell's [ScanFlow] steps turn those names into Processing then Viewer routes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanFlowNavigationTest {

    @TempDir
    lateinit var dir: File

    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private val scansRoot get() = File(dir, "scans")

    private class FakeTracker : CaptureTracker {
        val channel = Channel<TrackedFrame>(Channel.UNLIMITED)
        override val status: StateFlow<PoseSourceStatus> = MutableStateFlow(PoseSourceStatus.Running)
        override val frames: Flow<TrackedFrame> = channel.receiveAsFlow()
        override suspend fun start() = Unit
        override fun setForeground(inForeground: Boolean) = Unit
        override fun setPreview(target: PreviewTarget?): Job = Job().apply { complete() }
        override fun release() = Unit
    }

    /** A textured 640 x 480 image (sharp enough to keep). */
    private class TexturedImage : CameraImage {
        override val width = 640
        override val height = 480
        override fun toRgb() = RgbImage(width, height, ByteArray(width * height * 4) { 0x80.toByte() })
        override fun luma(): LumaPlane {
            val bytes = ByteArray(width * height) { i -> (((i % width) xor (i / width)) * 37).toByte() }
            return LumaPlane(width, height, width, bytes)
        }
        override fun close() = Unit
    }

    /** Frame [i] of [count] once round a 0.3 m circle about a 27-point grid, looking in. */
    private fun frame(i: Int, count: Int): TrackedFrame {
        val theta = (2 * PI * i / count).toFloat()
        val p = FloatArray(81)
        var o = 0
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            p[o++] = 0.05f * x
            p[o++] = 0.05f * y
            p[o++] = 0.05f * z
        }
        return TrackedFrame(
            // 0.3 s apart: 18 degrees a frame is 60 degrees a second, slow enough that no Slow down warning holds frames.
            timestampNs = 300_000_000L * i,
            trackingState = TrackingState.TRACKING,
            pose = CameraPose(
                Quat.fromAxisAngle(Vec3(0f, 1f, 0f), theta),
                Vec3(0.3f * sin(theta), 0f, 0.3f * cos(theta)),
            ),
            intrinsics = CameraIntrinsics(500f, 500f, 319.5f, 239.5f, 640, 480),
            image = TexturedImage(),
            points = FeaturePoints(27, p, FloatArray(27) { 1f }),
            depth = null,
        )
    }

    /** Fake trainer: two steps, the last carrying the seed splat. */
    private class SeedStepTrainer : Trainer {
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            val cloud = SeedInit.fromSeeds(input.seedPoints)
            emit(TrainingProgress(1, 2, 1f, cloud.count, 1L, null))
            emit(TrainingProgress(2, 2, 0.5f, cloud.count, 2L, cloud))
        }
    }

    /** Fake trainer that never finishes. */
    private class EndlessTrainer : Trainer {
        override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
            emit(TrainingProgress(1, 10, 1f, 4, 1L, null))
            awaitCancellation()
        }
    }

    private val decoder = KeyframeDecoder { _, longSide ->
        val (w, h) = trainingSize(640, 480, longSide)
        RgbImage(w, h, ByteArray(w * h * 4) { 0x7f })
    }
    private val thermal = object : ThermalMonitor {
        override val severe = MutableStateFlow(false)
    }

    private fun TestScope.repository(trainer: Trainer = SeedStepTrainer()) = ProcessingRepository(
        trainer, decoder, thermal, TrainingConfig(imageLongSide = 64), FreeSpace { Long.MAX_VALUE },
        StandardTestDispatcher(testScheduler), ThumbnailWriter({ _, _, _, _ -> }, 0),
    )

    /** Starts runs beside the test, as the service would (not in backgroundScope: advanceUntilIdle skips that). */
    private class Starter(val scope: TestScope, val repository: ProcessingRepository) : ProcessingStarter {
        val started = mutableListOf<File>()
        override fun start(scan: File) {
            started += scan
            scope.launch { repository.process(scan) }
        }
    }

    private inline fun <reified T : ViewModel> viewModel(key: String = "vm", crossinline create: () -> T): T =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
            },
        )[key, T::class.java]

    private val deleted = mutableListOf<String>()

    private fun memoryStore() = object : PreferenceStore {
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) = Unit
    }

    private fun processingViewModel(scanId: String, repository: ProcessingRepository, starter: ProcessingStarter) =
        viewModel(key = "processing:$scanId") {
            ProcessingViewModel(
                repository,
                starter,
                ScanDeleter { deleted += it },
                object : NotificationAsk {
                    override var asked = false
                    override fun markAsked() {
                        asked = true
                    }
                },
                object : ExportTarget {
                    override fun openDocument(uri: String) = throw IOException("not in tests")
                    override fun openShareFile(fileName: String) = throw IOException("not in tests")
                    override fun shareUri(fileName: String) = throw IOException("not in tests")
                    override fun createInFolder(treeUri: String, fileName: String) = throw IOException("not in tests")
                    override fun deleteDocument(uri: String) = Unit
                    override fun clearShareFiles() = Unit
                    override fun freeBytes(): Long? = null
                },
                SettingsPreferences(memoryStore()),
                ScanFolders(scansRoot),
                SavedStateHandle(mapOf(ProcessingViewModel.ARG_SCAN_ID to scanId)),
            )
        }

    private fun writeBundle(id: String) {
        val folder = File(scansRoot, id).apply { mkdirs() }
        val pose = CameraPose(Quat.IDENTITY, Vec3(0f, 0f, 0f))
        val camera = CameraIntrinsics(500f, 500f, 319.5f, 239.5f, 640, 480)
        val frames = List(2) { BundleFrameInput(it * 1_000L, camera, pose, byteArrayOf(1, 2, 3)) }
        val seeds = SeedPoints(
            4,
            floatArrayOf(0f, 0f, -1f, 0.1f, 0f, -1f, 0f, 0.1f, -1f, 0f, 0f, -1.1f),
            ByteArray(12) { 100 },
        )
        CaptureBundleWriter.write(folder, frames, seeds)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `capture done opens processing, which builds the splat and opens the viewer`() = runTest(scheduler) {
        val tracker = FakeTracker()
        val worker = StandardTestDispatcher(testScheduler)
        val recorder = CaptureRecorder(
            scansRoot, File(dir, "recording"), JpegEncoder { _, _ -> ByteArray(4) }, worker, worker,
            newScanId = { "scan_e2e" },
        )
        val settings = SettingsPreferences(
            object : PreferenceStore {
                override fun getBoolean(key: String, default: Boolean) = default
                override fun putBoolean(key: String, value: Boolean) = Unit
                override fun getString(key: String): String? = null
                override fun putString(key: String, value: String) = Unit
            },
        )
        val capture = viewModel { CaptureViewModel(tracker, recorder, settings) }
        val saved = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { capture.openProcessing.collect { saved += it } }

        capture.onIntent(CaptureIntent.Record)
        runCurrent()
        repeat(20) {
            tracker.channel.send(frame(it, 20))
            runCurrent()
        }
        capture.onIntent(CaptureIntent.Stop)
        advanceUntilIdle()

        assertTrue(capture.uiState.value.recorder is RecorderState.Saved, "was ${capture.uiState.value.recorder}")
        assertEquals(listOf("scan_e2e"), saved, "the saved scan is named once")
        val toProcessing = ScanFlow.afterCapture(saved.single())
        assertEquals(NavigationDestination.Processing("scan_e2e"), toProcessing.destination)
        assertEquals(NavigationDestination.Capture, toProcessing.replaces)

        val repository = repository()
        val starter = Starter(this, repository)
        val processing = processingViewModel("scan_e2e", repository, starter)
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            processing.effects.collect { if (it is ProcessingEvent.OpenViewer) opened += it.scanId }
        }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), opened, "Done waits for View it")
        processing.onIntent(ProcessingIntent.Open)
        advanceUntilIdle()

        assertEquals(listOf(File(scansRoot, "scan_e2e")), starter.started, "an unprocessed scan starts at once")
        assertTrue(processing.uiState.value.state is ProcessingState.Done, "was ${processing.uiState.value.state}")
        assertTrue(File(scansRoot, "scan_e2e/${ProcessingRepository.SPLAT_FILE}").isFile)
        assertEquals(listOf("scan_e2e"), opened)
        val toViewer = ScanFlow.afterProcessing(opened.single())
        assertEquals(NavigationDestination.Viewer("scan_e2e"), toViewer.destination)
        assertEquals(NavigationDestination.Processing("scan_e2e"), toViewer.replaces)
    }

    @Test
    fun `a run the app was killed in shows failed, does not restart by itself, and Retry builds it`() =
        runTest(scheduler) {
            writeBundle("killed")
            // What process death leaves behind.
            File(scansRoot, "killed/${ProcessingRepository.RUNNING_MARKER}").writeText("")
            val repository = repository()
            val starter = Starter(this, repository)
            val processing = processingViewModel("killed", repository, starter)
            advanceUntilIdle()

            assertEquals(ProcessingState.Failed(ProcessingRepository.INTERRUPTED), processing.uiState.value.state)
            assertTrue(processing.uiState.value.canRetry)
            assertFalse(processing.uiState.value.canCancel)
            assertTrue(starter.started.isEmpty())

            processing.onIntent(ProcessingIntent.Retry)
            advanceUntilIdle()

            assertEquals(1, starter.started.size)
            assertTrue(processing.uiState.value.state is ProcessingState.Done)
        }

    @Test
    fun `progress shows as a fraction and Cancel stops the run`() = runTest(scheduler) {
        writeBundle("long")
        val repository = repository(EndlessTrainer())
        val starter = Starter(this, repository)
        val processing = processingViewModel("long", repository, starter)
        advanceUntilIdle()

        assertEquals(ProcessingStage.BUILD, processing.uiState.value.progress?.stage)
        assertEquals(0.1f + 0.55f * (0.1f / 0.6f), processing.uiState.value.progress!!.overall, 1e-5f)
        assertTrue(processing.uiState.value.canCancel)

        processing.onIntent(ProcessingIntent.Cancel)
        advanceUntilIdle()

        assertEquals(ProcessingState.Cancelled, processing.uiState.value.state)
        assertTrue(processing.uiState.value.canRetry)
        assertNull(processing.uiState.value.progress)
    }

    @Test
    fun `a scan id with no folder shows missing and starts nothing`() = runTest(scheduler) {
        val repository = repository()
        val starter = Starter(this, repository)
        val processing = processingViewModel("gone", repository, starter)
        val traversal = processingViewModel("../x", repository, starter)
        advanceUntilIdle()

        assertTrue(processing.uiState.value.missing)
        assertTrue(traversal.uiState.value.missing)
        assertFalse(processing.uiState.value.canRetry)
        processing.onIntent(ProcessingIntent.Retry)
        assertTrue(starter.started.isEmpty())
    }
}
