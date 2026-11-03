package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.JpegEncoder
import com.huntercoles.splatman.capture.recording.PoseFreeRecorder
import com.huntercoles.splatman.capture.recording.PoseFreeState
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Optional

/** The capture view model with a [PoseFreeRecorder] (the foss build, #219): no tracker, no subject, gyro coverage. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelPoseFreeTest {
    private class FakeTracker : CaptureTracker {
        var starts = 0
        val previews = mutableListOf<PreviewTarget?>()
        override val status: StateFlow<PoseSourceStatus> = MutableStateFlow(PoseSourceStatus.Idle)
        override val frames: Flow<TrackedFrame> = emptyFlow()
        override suspend fun start() {
            starts++
        }
        override fun setForeground(inForeground: Boolean) = Unit
        override fun setPreview(target: PreviewTarget?): Job {
            previews += target
            return done()
        }
        override fun release() = Unit
    }

    /** Records nothing: tests push its [state] by hand, as the real recorder would after each frame. */
    private class FakeRecorder : PoseFreeRecorder {
        val mutableState = MutableStateFlow<PoseFreeState>(PoseFreeState.Idle)
        override val state: StateFlow<PoseFreeState> = mutableState
        var openResult = true
        var cameraOpen = false
        var opens = 0
        var starts = 0
        var stops = 0
        var cancels = 0
        var releases = 0
        val pauses = mutableListOf<Boolean>()
        val foreground = mutableListOf<Boolean>()
        val previews = mutableListOf<PreviewTarget?>()

        override suspend fun open(): Boolean {
            opens++
            cameraOpen = openResult
            mutableState.value = if (openResult) PoseFreeState.Previewing() else PoseFreeState.Failed("refused")
            return openResult
        }
        override fun setPreview(target: PreviewTarget?): Job {
            previews += target
            return done()
        }
        override fun setForeground(inForeground: Boolean) {
            foreground += inForeground
        }
        override fun start(): Boolean {
            if (!cameraOpen) return false
            starts++
            mutableState.value = PoseFreeState.Recording(0, 0f, CoverageCells(), emptySet(), 0)
            return true
        }
        override fun pause(paused: Boolean) {
            pauses += paused
        }
        override fun stop(): Job {
            stops++
            return done()
        }
        override fun cancel(): Job {
            cancels++
            mutableState.value = PoseFreeState.Previewing()
            return done()
        }
        override fun release() {
            releases++
        }

        /** The camera went away (another app took it). */
        fun loseCamera(reason: String) {
            cameraOpen = false
            mutableState.value = PoseFreeState.Failed(reason)
        }
    }

    private class FakeAccess : PoseFreeAccess {
        var granted = true
        var answer = TrackingCheck(TrackingAvailability.SUPPORTED, "pose-free capture available")
        var now = 0L
        override fun cameraGranted(): Boolean = granted
        override suspend fun check(): TrackingCheck = answer
        override fun nowNs(): Long = now
    }

    private class MapStore : PreferenceStore {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val settings = SettingsPreferences(MapStore())
    private val tracker = FakeTracker()
    private val poseFree = FakeRecorder()
    private val access = FakeAccess()
    private val store = ViewModelStore()
    private val background = StandardTestDispatcher()
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

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
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CaptureViewModel(tracker, recorder, settings, Optional.of(poseFree), access) as T
        },
    )[CaptureViewModel::class.java]

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        store.clear()
        Dispatchers.resetMain()
    }

    private val ms = 1_000_000L

    /** A recording update [i] frames in, 33 ms apart, as the recorder publishes one per frame. */
    private fun frame(
        i: Int,
        keyframes: Int = i,
        cells: CoverageCells = CoverageCells(),
        warnings: Set<GuidanceWarning> = emptySet(),
    ) {
        access.now = (i + 1) * 33 * ms
        poseFree.mutableState.value = PoseFreeState.Recording(keyframes, cells.fraction, cells, warnings, i)
    }

    private val allCells = (1 shl Coverage.SEGMENTS) - 1

    @Test
    fun `opens the pose-free recorder, never the tracker, and aims with the ready pill`() {
        val vm = viewModel()
        val s = vm.uiState.value
        assertEquals(1, poseFree.opens)
        assertEquals(0, tracker.starts)
        assertTrue(s.poseFree)
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING, paused = false), s.phase)
        assertEquals(CaptureStage.AIM, s.stage)
        assertEquals(StatusPill.READY, s.status)
        assertEquals(Instruction.AIM, s.instruction)
    }

    @Test
    fun `starting shows the starting pill until the camera is open`() {
        assertEquals(CaptureStage.STARTING, poseFreeStage(PoseFreeState.Opening, paused = false))
        assertEquals(StatusPill.STARTING, poseFreePill(CaptureStage.STARTING))
    }

    @Test
    fun `record shows the recording pill and counts keyframes`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        assertEquals(1, poseFree.starts)
        frame(1, keyframes = 3)
        val s = vm.uiState.value
        assertEquals(CaptureStage.CAPTURING, s.stage)
        assertEquals(StatusPill.RECORDING, s.status)
        assertEquals(3, s.framesKept)
        assertEquals(Instruction.WALK, s.instruction)
        assertTrue(s.recorder is RecorderState.Recording)
    }

    @Test
    fun `stop saves and the saved scan opens Processing`() {
        val vm = viewModel()
        val opened = mutableListOf<String>()
        vm.openProcessing.onEach { opened += it }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 4)
        vm.onIntent(CaptureIntent.Stop)
        assertEquals(1, poseFree.stops)
        poseFree.mutableState.value = PoseFreeState.Saving(4)
        assertEquals(CaptureStage.FINISHING, vm.uiState.value.stage)
        poseFree.mutableState.value = PoseFreeState.Saved("scan_1", 4)
        assertEquals(listOf("scan_1"), opened)
    }

    @Test
    fun `stop with no keyframe says nothing was recorded and opens nothing`() {
        val vm = viewModel()
        val opened = mutableListOf<String>()
        vm.openProcessing.onEach { opened += it }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 0)
        vm.onIntent(CaptureIntent.Stop)
        poseFree.mutableState.value = PoseFreeState.Failed("no frames")
        val s = vm.uiState.value
        assertEquals(RecorderState.NothingRecorded, s.recorder)
        assertEquals(CaptureStage.AIM, s.stage)
        assertEquals(Instruction.NOTHING_RECORDED, s.instruction)
        assertTrue(s.phase is CapturePhase.Preview)
        assertEquals(emptyList<String>(), opened)
        // The camera is still open: record again at once.
        vm.onIntent(CaptureIntent.Record)
        assertEquals(2, poseFree.starts)
        assertEquals(CaptureStage.CAPTURING, vm.uiState.value.stage)
    }

    @Test
    fun `a failed save says so`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 2)
        vm.onIntent(CaptureIntent.Stop)
        poseFree.mutableState.value = PoseFreeState.Failed("disk full")
        assertEquals(RecorderState.Failed("disk full"), vm.uiState.value.recorder)
        assertEquals(Instruction.SAVE_FAILED, vm.uiState.value.instruction)
    }

    @Test
    fun `cancel discards the recording`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 2)
        vm.onIntent(CaptureIntent.Cancel)
        assertEquals(1, poseFree.cancels)
        val s = vm.uiState.value
        assertEquals(RecorderState.Idle, s.recorder)
        assertEquals(CaptureStage.AIM, s.stage)
        assertEquals(0, s.framesKept)
    }

    @Test
    fun `a FAST warning shows the move-slower warning and a reject haptic`() {
        val vm = viewModel()
        val haptics = mutableListOf<CaptureHaptic>()
        vm.haptics.onEach { haptics += it }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        frame(1, warnings = setOf(GuidanceWarning.FAST))
        assertEquals(GuidanceWarning.FAST, vm.uiState.value.warning)
        assertNull(vm.uiState.value.instruction)
        assertEquals(listOf(CaptureHaptic.CONFIRM, CaptureHaptic.REJECT), haptics)
    }

    @Test
    fun `too dark wins over slow down`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, warnings = setOf(GuidanceWarning.FAST, GuidanceWarning.DARK))
        assertEquals(GuidanceWarning.DARK, vm.uiState.value.warning)
    }

    @Test
    fun `a tap does nothing in pose-free mode`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.ViewSize(1080, 2400))
        val before = vm.uiState.value
        vm.onIntent(CaptureIntent.Tap(540f, 1200f))
        assertEquals(before, vm.uiState.value)
        assertFalse(vm.uiState.value.subjectSet)
        assertNull(vm.uiState.value.reticle)
    }

    @Test
    fun `pause and resume reach the recorder and the pill`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1)
        vm.onIntent(CaptureIntent.Pause)
        frame(2)
        assertEquals(CaptureStage.PAUSED, vm.uiState.value.stage)
        assertEquals(StatusPill.PAUSED, vm.uiState.value.status)
        vm.onIntent(CaptureIntent.Resume)
        assertEquals(listOf(true, false), poseFree.pauses)
        assertEquals(CaptureStage.CAPTURING, vm.uiState.value.stage)
    }

    @Test
    fun `elapsed time counts while recording and not while paused`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        for (i in 1..4) frame(i)
        assertEquals(99L, vm.uiState.value.elapsedMs)
        vm.onIntent(CaptureIntent.Pause)
        for (i in 5..7) frame(i)
        assertEquals(99L, vm.uiState.value.elapsedMs)
    }

    @Test
    fun `elapsed time counts slow frames but not the background`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        fun at(timeMs: Long, keyframes: Int) {
            access.now = timeMs * ms
            poseFree.mutableState.value = PoseFreeState.Recording(keyframes, 0f, CoverageCells(), emptySet(), 0)
        }
        at(1_000, 1)
        at(1_500, 2) // a slow phone: one update every 500 ms
        at(2_000, 3)
        assertEquals(1_000L, vm.uiState.value.elapsedMs)
        vm.onIntent(CaptureIntent.Foreground(false))
        vm.onIntent(CaptureIntent.Foreground(true))
        at(60_000, 4) // a minute in the background
        at(60_500, 5)
        assertEquals(1_500L, vm.uiState.value.elapsedMs)
    }

    @Test
    fun `full gyro coverage makes Finish ready with a confirm haptic`() {
        val vm = viewModel()
        val haptics = mutableListOf<CaptureHaptic>()
        vm.haptics.onEach { haptics += it }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        frame(1, cells = CoverageCells(allCells, allCells, allCells))
        assertTrue(vm.uiState.value.ready)
        assertEquals(Instruction.READY, vm.uiState.value.instruction)
        assertEquals(listOf(CaptureHaptic.CONFIRM, CaptureHaptic.CONFIRM), haptics)
    }

    @Test
    fun `the preview surface goes to the recorder, not the tracker`() {
        val vm = viewModel()
        vm.setPreview(null)
        assertEquals(listOf<PreviewTarget?>(null), poseFree.previews)
        assertTrue(tracker.previews.isEmpty())
    }

    @Test
    fun `background mid-recording pauses the camera and keeps the recording`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 2)
        vm.onIntent(CaptureIntent.Foreground(false))
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING, paused = true), vm.uiState.value.phase)
        vm.onIntent(CaptureIntent.Foreground(true))
        assertEquals(listOf(false, true), poseFree.foreground)
        frame(2, keyframes = 3)
        val s = vm.uiState.value
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING, paused = false), s.phase)
        assertEquals(3, s.framesKept)
        assertEquals(1, poseFree.opens)
    }

    @Test
    fun `permission denied, then granted in Settings and back opens the camera`() {
        access.granted = false
        val vm = viewModel()
        assertEquals(CapturePhase.PermissionNeeded, vm.uiState.value.phase)
        assertEquals(0, poseFree.opens)
        vm.onIntent(CaptureIntent.PermissionResult(false))
        assertEquals(CapturePhase.PermissionDenied, vm.uiState.value.phase)
        vm.onIntent(CaptureIntent.Foreground(false))
        access.granted = true
        vm.onIntent(CaptureIntent.Foreground(true))
        assertEquals(1, poseFree.opens)
        assertEquals(CapturePhase.Preview(TrackingHint.TRACKING, paused = false), vm.uiState.value.phase)
    }

    @Test
    fun `a phone without a back camera shows unsupported`() {
        access.answer = TrackingCheck(TrackingAvailability.UNSUPPORTED, "foss build: no back camera")
        val vm = viewModel()
        assertEquals(CapturePhase.Unsupported("foss build: no back camera"), vm.uiState.value.phase)
        assertEquals(0, poseFree.opens)
    }

    @Test
    fun `a camera that will not open fails and try again opens it`() {
        poseFree.openResult = false
        val vm = viewModel()
        assertEquals(CapturePhase.Failed("refused"), vm.uiState.value.phase)
        poseFree.openResult = true
        vm.onIntent(CaptureIntent.Retry)
        assertEquals(2, poseFree.opens)
        assertTrue(vm.uiState.value.phase is CapturePhase.Preview)
    }

    @Test
    fun `camera lost mid-recording says so, then record shows the failure and try again reopens`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 2)
        poseFree.loseCamera("camera in use")
        assertEquals(RecorderState.Failed("camera in use"), vm.uiState.value.recorder)
        vm.onIntent(CaptureIntent.Record)
        assertEquals(CapturePhase.Failed("camera in use"), vm.uiState.value.phase)
        vm.onIntent(CaptureIntent.Retry)
        assertEquals(2, poseFree.opens)
        assertTrue(vm.uiState.value.phase is CapturePhase.Preview)
    }

    @Test
    fun `camera lost while aiming shows the failure`() {
        val vm = viewModel()
        poseFree.loseCamera("camera disconnected")
        assertEquals(CapturePhase.Failed("camera disconnected"), vm.uiState.value.phase)
    }

    @Test
    fun `close with frames asks, discard cancels and leaves`() {
        val vm = viewModel()
        var left = 0
        vm.leave.onEach { left++ }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 2)
        vm.onIntent(CaptureIntent.Close)
        assertTrue(vm.uiState.value.confirmDiscard)
        assertEquals(0, left)
        vm.onIntent(CaptureIntent.Discard)
        assertEquals(1, poseFree.cancels)
        assertEquals(1, left)
    }

    @Test
    fun `clearing the view model releases the recorder`() {
        viewModel()
        store.clear()
        assertEquals(1, poseFree.releases)
    }

    private companion object {
        fun done(): Job = Job().apply { complete() }
    }
}
