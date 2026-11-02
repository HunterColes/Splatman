package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.JpegEncoder
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.CameraImage
import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private class FakeTracker : CaptureTracker {
        val mutableStatus = MutableStateFlow<PoseSourceStatus>(PoseSourceStatus.Idle)
        val channel = Channel<TrackedFrame>(Channel.UNLIMITED)
        var starts = 0
        val foreground = mutableListOf<Boolean>()
        val previews = mutableListOf<PreviewTarget?>()
        var releases = 0

        override val status: StateFlow<PoseSourceStatus> = mutableStatus
        override val frames: Flow<TrackedFrame> = channel.receiveAsFlow()
        override suspend fun start() {
            starts++
        }
        override fun setForeground(inForeground: Boolean) {
            foreground += inForeground
        }
        override fun setPreview(target: PreviewTarget?): Job {
            previews += target
            return Job().apply { complete() }
        }
        override fun release() {
            releases++
        }
    }

    private class FakeImage : CameraImage {
        var closes = 0
        override val width = 2
        override val height = 2
        override fun toRgb() = RgbImage(2, 2, ByteArray(16))
        override fun close() {
            closes++
        }
    }

    private fun frame(state: TrackingState, failure: TrackingFailure, image: FakeImage = FakeImage()) = TrackedFrame(
        timestampNs = 1L,
        trackingState = state,
        pose = CameraPose(Quat.IDENTITY, Vec3.ZERO),
        intrinsics = CameraIntrinsics(fx = 2f, fy = 2f, cx = 1f, cy = 1f, width = 2, height = 2),
        image = image,
        points = FeaturePoints.EMPTY,
        depth = null,
        trackingFailure = failure,
    )

    private val tracker = FakeTracker()
    private val store = ViewModelStore()
    private val background = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    private val recorder by lazy {
        CaptureRecorder(
            scansRoot = File(dir, "scans"),
            stagingRoot = File(dir, "recording"),
            encoder = JpegEncoder { _, _ -> ByteArray(4) },
            worker = background,
            io = background,
        )
    }

    private fun viewModel(): CaptureViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(tracker, recorder) as T
        },
    )[CaptureViewModel::class.java]

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `starts the tracker and shows checking while idle`() {
        val vm = viewModel()
        assertEquals(1, tracker.starts)
        assertEquals(CapturePhase.Checking, vm.uiState.value.phase)
    }

    @Test
    fun `maps every tracking failure reason to its hint`() {
        val expected = mapOf(
            TrackingFailure.NONE to TrackingHint.LOOKING_FOR_SURFACES,
            TrackingFailure.BAD_STATE to TrackingHint.TRACKING_LOST,
            TrackingFailure.INSUFFICIENT_LIGHT to TrackingHint.MORE_LIGHT,
            TrackingFailure.EXCESSIVE_MOTION to TrackingHint.MOVE_SLOWER,
            TrackingFailure.INSUFFICIENT_FEATURES to TrackingHint.MORE_DETAIL,
            TrackingFailure.CAMERA_UNAVAILABLE to TrackingHint.CAMERA_IN_USE,
        )
        assertEquals(TrackingFailure.entries.toSet(), expected.keys)
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.Running
        for ((failure, hint) in expected) {
            tracker.channel.trySend(frame(TrackingState.PAUSED, failure))
            assertEquals(CapturePhase.Preview(hint, paused = false), vm.uiState.value.phase, "$failure")
        }
    }

    @Test
    fun `maps tracking states without a failure`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.Running
        assertEquals(CapturePhase.Preview(TrackingHint.LOOKING_FOR_SURFACES, paused = false), vm.uiState.value.phase)
        tracker.channel.trySend(frame(TrackingState.TRACKING, TrackingFailure.NONE))
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING, paused = false), vm.uiState.value.phase)
        tracker.channel.trySend(frame(TrackingState.STOPPED, TrackingFailure.NONE))
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING_LOST, paused = false), vm.uiState.value.phase)
        tracker.mutableStatus.value = PoseSourceStatus.Paused
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING_LOST, paused = true), vm.uiState.value.phase)
    }

    @Test
    fun `closes every frame it reads`() {
        viewModel()
        val images = List(3) { FakeImage() }
        images.forEach { tracker.channel.trySend(frame(TrackingState.TRACKING, TrackingFailure.NONE, it)) }
        assertEquals(listOf(1, 1, 1), images.map { it.closes })
    }

    @Test
    fun `maps each availability state`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.Unavailable(TrackingAvailability.UNSUPPORTED)
        assertEquals(CapturePhase.Unsupported, vm.uiState.value.phase)
        tracker.mutableStatus.value = PoseSourceStatus.Unavailable(TrackingAvailability.INSTALL_NEEDED)
        assertEquals(CapturePhase.InstallNeeded, vm.uiState.value.phase)
        tracker.mutableStatus.value = PoseSourceStatus.Unavailable(TrackingAvailability.SUPPORTED)
        assertEquals(CapturePhase.Checking, vm.uiState.value.phase)
    }

    @Test
    fun `install under way waits for resume, a finished install starts again`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.Unavailable(TrackingAvailability.INSTALL_NEEDED)
        vm.onIntent(CaptureIntent.InstallResult(TrackingAvailability.INSTALL_NEEDED))
        assertTrue(vm.uiState.value.installPending)
        assertEquals(1, tracker.starts)
        vm.onIntent(CaptureIntent.InstallResult(TrackingAvailability.SUPPORTED))
        assertFalse(vm.uiState.value.installPending)
        assertEquals(2, tracker.starts)
    }

    @Test
    fun `permission needed, then denied, then granted`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.NeedsPermission
        assertEquals(CapturePhase.PermissionNeeded, vm.uiState.value.phase)
        vm.onIntent(CaptureIntent.PermissionResult(granted = false))
        assertEquals(CapturePhase.PermissionDenied, vm.uiState.value.phase)
        assertEquals(1, tracker.starts)
        vm.onIntent(CaptureIntent.PermissionResult(granted = true))
        assertEquals(2, tracker.starts)
    }

    @Test
    fun `resume rechecks a missing permission`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.NeedsPermission
        vm.onIntent(CaptureIntent.Foreground(true))
        assertEquals(2, tracker.starts)
        tracker.mutableStatus.value = PoseSourceStatus.Running
        vm.onIntent(CaptureIntent.Foreground(true))
        assertEquals(2, tracker.starts)
    }

    @Test
    fun `failure shows its reason and retry starts again`() {
        val vm = viewModel()
        tracker.mutableStatus.value = PoseSourceStatus.Failed("camera busy")
        assertEquals(CapturePhase.Failed("camera busy"), vm.uiState.value.phase)
        vm.onIntent(CaptureIntent.Retry)
        assertEquals(2, tracker.starts)
    }

    @Test
    fun `leaving the foreground pauses the session`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Foreground(false))
        vm.onIntent(CaptureIntent.Foreground(true))
        assertEquals(listOf(false, true), tracker.foreground)
    }

    @Test
    fun `preview surface goes to the tracker`() {
        val vm = viewModel()
        vm.setPreview(null)
        assertEquals(listOf<PreviewTarget?>(null), tracker.previews)
    }

    @Test
    fun `record and cancel drive the recorder and show its state`() {
        val vm = viewModel()
        assertEquals(RecorderState.Idle, vm.uiState.value.recorder)
        vm.onIntent(CaptureIntent.Record)
        assertEquals(RecorderState.Recording(0, 0f, 0, 0), vm.uiState.value.recorder)
        vm.onIntent(CaptureIntent.Cancel)
        background.scheduler.advanceUntilIdle()
        assertEquals(RecorderState.Idle, vm.uiState.value.recorder)
    }

    @Test
    fun `stop with nothing kept says so`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        background.scheduler.advanceUntilIdle()
        vm.onIntent(CaptureIntent.Stop)
        background.scheduler.advanceUntilIdle()
        assertEquals(RecorderState.NothingRecorded, vm.uiState.value.recorder)
    }

    @Test
    fun `frames go to the recorder while recording, which closes them`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        background.scheduler.advanceUntilIdle()
        val image = FakeImage()
        tracker.channel.trySend(frame(TrackingState.PAUSED, TrackingFailure.NONE, image))
        assertEquals(0, image.closes)
        background.scheduler.advanceUntilIdle()
        assertEquals(1, image.closes)
        assertTrue(vm.uiState.value.recorder is RecorderState.Recording)
    }

    @Test
    fun `clearing the view model releases the tracker`() {
        viewModel()
        assertEquals(0, tracker.releases)
        store.clear()
        assertEquals(1, tracker.releases)
    }
}
