package com.huntercoles.splatman.capture.presentation.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CaptureRecorder
import com.huntercoles.splatman.capture.recording.CoverageBand
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.JpegEncoder
import com.huntercoles.splatman.capture.recording.PoseFreeRecorder
import com.huntercoles.splatman.capture.recording.PoseFreeState
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.recording.RoomCells
import com.huntercoles.splatman.capture.tracking.PoseSourceStatus
import com.huntercoles.splatman.capture.tracking.TrackedFrame
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.core.preferences.CapturePreferences
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.capture.ScanMode
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

/** Room mode on the pose-free path (#300, ADR 0015 section 5): the switch, the gyro Seen gauge, the Finish rule. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelRoomTest {
    private class FakeTracker : CaptureTracker {
        override val status: StateFlow<PoseSourceStatus> = MutableStateFlow(PoseSourceStatus.Idle)
        override val frames: Flow<TrackedFrame> = emptyFlow()
        override suspend fun start() = Unit
        override fun setForeground(inForeground: Boolean) = Unit
        override fun setPreview(target: PreviewTarget?): Job = done()
        override fun release() = Unit
    }

    /** Records nothing: tests push its [state] by hand, as the real recorder would after each frame. */
    private class FakeRecorder : PoseFreeRecorder {
        val mutableState = MutableStateFlow<PoseFreeState>(PoseFreeState.Idle)
        override val state: StateFlow<PoseFreeState> = mutableState
        val modes = mutableListOf<ScanMode>()

        override suspend fun open(): Boolean {
            mutableState.value = PoseFreeState.Previewing()
            return true
        }
        override fun setPreview(target: PreviewTarget?): Job = done()
        override fun setForeground(inForeground: Boolean) = Unit
        override fun start(): Boolean = start(ScanMode.OBJECT)
        override fun start(mode: ScanMode): Boolean {
            modes += mode
            mutableState.value = PoseFreeState.Recording(0, 0f, CoverageCells(), emptySet(), 0)
            return true
        }
        override fun pause(paused: Boolean) = Unit
        override fun stop(): Job = done()
        override fun cancel(): Job {
            mutableState.value = PoseFreeState.Previewing()
            return done()
        }
        override fun release() = Unit
    }

    private class FakeAccess(private val gyroscope: Boolean) : PoseFreeAccess {
        var now = 0L
        override fun cameraGranted(): Boolean = true
        override suspend fun check(): TrackingCheck = TrackingCheck(TrackingAvailability.SUPPORTED, "ok")
        override fun nowNs(): Long = now
        override fun hasGyroscope(): Boolean = gyroscope
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

    private val captureStore = MapStore()
    private val poseFree = FakeRecorder()
    private val store = ViewModelStore()
    private val background = StandardTestDispatcher()
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private var access = FakeAccess(gyroscope = true)

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

    private fun viewModel(gyroscope: Boolean = true): CaptureViewModel {
        access = FakeAccess(gyroscope)
        return ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                    FakeTracker(),
                    recorder,
                    SettingsPreferences(MapStore()),
                    Optional.of(poseFree),
                    access,
                    CapturePreferences(captureStore),
                ) as T
            },
        )[CaptureViewModel::class.java]
    }

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

    /** A recording update [i] frames in, 33 ms apart. */
    private fun frame(
        i: Int,
        keyframes: Int = i,
        cells: CoverageCells = CoverageCells(),
        warnings: Set<GuidanceWarning> = emptySet(),
        facing: Int = 0,
    ) {
        access.now = (i + 1) * 33 * ms
        poseFree.mutableState.value = PoseFreeState.Recording(keyframes, cells.fraction, cells, warnings, 0, facing)
    }

    private fun bits(n: Int): Int = (1 shl n) - 1

    /** [level] wall cells, [high] ceiling cells and [low] floor cells Seen. */
    private fun seen(level: Int, high: Int = 0, low: Int = 0) = CoverageCells(bits(low), bits(level), bits(high))

    private fun roomViewModel(gyroscope: Boolean = true): CaptureViewModel {
        val vm = viewModel(gyroscope)
        vm.onIntent(CaptureIntent.SelectMode(ScanMode.ROOM))
        return vm
    }

    @Test
    fun `SelectMode ROOM in Aim switches, records a room and is saved for the next scan`() {
        val vm = viewModel()
        assertEquals(ScanMode.OBJECT, vm.uiState.value.mode)
        assertTrue(vm.uiState.value.modeSwitchVisible)
        vm.onIntent(CaptureIntent.SelectMode(ScanMode.ROOM))
        val s = vm.uiState.value
        assertEquals(ScanMode.ROOM, s.mode)
        assertEquals(Instruction.ROOM_AIM, s.instruction)
        assertEquals(RoomCells(), s.roomCells)
        assertEquals(CapturePreferences.ROOM, CapturePreferences(captureStore).scanMode.value)
        vm.onIntent(CaptureIntent.Record)
        assertEquals(listOf(ScanMode.ROOM), poseFree.modes)
    }

    @Test
    fun `the saved mode is the first mode`() {
        CapturePreferences(captureStore).setScanMode(CapturePreferences.ROOM)
        assertEquals(ScanMode.ROOM, viewModel().uiState.value.mode)
    }

    @Test
    fun `SelectMode after the first kept frame is ignored and the switch is hidden`() {
        val vm = viewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 1)
        assertFalse(vm.uiState.value.modeSwitchVisible)
        vm.onIntent(CaptureIntent.SelectMode(ScanMode.ROOM))
        assertEquals(ScanMode.OBJECT, vm.uiState.value.mode)
        assertNull(vm.uiState.value.roomCells)
        assertEquals(CapturePreferences.OBJECT, CapturePreferences(captureStore).scanMode.value)
        // A new scan shows it again.
        vm.onIntent(CaptureIntent.Cancel)
        assertTrue(vm.uiState.value.modeSwitchVisible)
        vm.onIntent(CaptureIntent.SelectMode(ScanMode.ROOM))
        assertEquals(ScanMode.ROOM, vm.uiState.value.mode)
    }

    @Test
    fun `the room gauge is the gyro Seen cells, never Covered`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        val cells = seen(level = 18)
        frame(1, keyframes = 10, cells = cells)
        val s = vm.uiState.value
        assertEquals(RoomCells(seen = cells), s.roomCells)
        assertEquals(0, s.roomCells!!.covered.total)
        assertEquals(18 * 100 / Coverage.CELLS, s.roomPercent)
        assertFalse(s.noGauge)
    }

    @Test
    fun `Finish unlocks at 51 Seen with 18 walls and 120 kept frames, not at 50 or 119`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 120, cells = seen(level = 24, high = 24, low = 2))
        assertFalse(vm.uiState.value.ready)
        frame(2, keyframes = 119, cells = seen(level = 24, high = 24, low = 3))
        assertFalse(vm.uiState.value.ready)
        frame(3, keyframes = 120, cells = seen(level = 17, high = 24, low = 10))
        assertFalse(vm.uiState.value.ready)
        frame(4, keyframes = 120, cells = seen(level = 24, high = 24, low = 3))
        assertTrue(vm.uiState.value.ready)
        assertEquals(Instruction.READY, vm.uiState.value.instruction)
    }

    @Test
    fun `without a gyroscope the gauge hides and 120 kept frames alone unlock Finish`() {
        val vm = roomViewModel(gyroscope = false)
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = 119)
        assertTrue(vm.uiState.value.noGauge)
        assertFalse(vm.uiState.value.ready)
        assertEquals(Instruction.ROOM_WALK, vm.uiState.value.instruction)
        frame(2, keyframes = 120)
        assertTrue(vm.uiState.value.ready)
    }

    @Test
    fun `the keyframe budget sets budgetReached, ROOM_FULL and ready`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, keyframes = PoseFreeRoom.BUDGET - 1)
        assertFalse(vm.uiState.value.budgetReached)
        frame(2, keyframes = PoseFreeRoom.BUDGET)
        val s = vm.uiState.value
        assertTrue(s.budgetReached)
        assertTrue(s.ready)
        assertEquals(Instruction.ROOM_FULL, s.instruction)
    }

    @Test
    fun `each further full turn of yaw shows walk-on for a while, spinning never makes ready`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        var i = 1
        // One turn, a segment a frame.
        repeat(Coverage.SEGMENTS) { frame(i++, keyframes = 5, facing = it) }
        assertEquals(Instruction.ROOM_WALK, vm.uiState.value.instruction)
        frame(i++, keyframes = 5, facing = 0)
        assertEquals(Instruction.ROOM_WALK_ON, vm.uiState.value.instruction)
        assertFalse(vm.uiState.value.ready)
        // It goes after WALK_ON_SHOW_MS ...
        access.now += PoseFreeRoom.WALK_ON_SHOW_MS * ms
        poseFree.mutableState.value = PoseFreeState.Recording(5, 0f, CoverageCells(), emptySet(), 1, 0)
        assertEquals(Instruction.ROOM_WALK, vm.uiState.value.instruction)
        // ... and comes back after the next full turn.
        val base = access.now
        repeat(Coverage.SEGMENTS) {
            access.now = base + (it + 1) * 33 * ms
            poseFree.mutableState.value =
                PoseFreeState.Recording(5, 0f, CoverageCells(), emptySet(), 2 + it, (it + 1) % Coverage.SEGMENTS)
        }
        assertEquals(Instruction.ROOM_WALK_ON, vm.uiState.value.instruction)
    }

    @Test
    fun `room guidance asks for walls, then the ceiling, then the floor`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, cells = seen(level = 11))
        assertEquals(Instruction.ROOM_WALK, vm.uiState.value.instruction)
        frame(2, cells = seen(level = 12))
        assertEquals(Instruction.ROOM_LOOK_UP, vm.uiState.value.instruction)
        frame(3, cells = seen(level = 12, high = 12))
        assertEquals(Instruction.ROOM_LOOK_DOWN, vm.uiState.value.instruction)
    }

    @Test
    fun `room warnings are the pose-free ones and replace the instruction`() {
        val vm = roomViewModel()
        vm.onIntent(CaptureIntent.Record)
        frame(1, warnings = setOf(GuidanceWarning.FAST, GuidanceWarning.DARK))
        assertEquals(GuidanceWarning.DARK, vm.uiState.value.warning)
        assertNull(vm.uiState.value.instruction)
    }

    @Test
    fun `Object mode keeps its rule and has no room state`() {
        val vm = viewModel()
        val haptics = mutableListOf<CaptureHaptic>()
        vm.haptics.onEach { haptics += it }.launchIn(scope)
        vm.onIntent(CaptureIntent.Record)
        val all = bits(Coverage.SEGMENTS)
        frame(1, keyframes = 5, cells = CoverageCells(all, all, 0))
        val s = vm.uiState.value
        assertTrue(s.ready)
        assertNull(s.roomCells)
        assertFalse(s.budgetReached)
        assertEquals(Instruction.READY, s.instruction)
        assertEquals(RecorderState.Recording(5, s.cells.fraction, 0, 0, s.cells), s.recorder)
    }

    @Test
    fun `the room ready rule and step are pure`() {
        assertTrue(PoseFreeRoom.ready(seen(24, 24, 3), PoseFreeRoom.MIN_KEYFRAMES, hasGyroscope = true))
        assertFalse(PoseFreeRoom.ready(seen(24, 24, 2), PoseFreeRoom.MIN_KEYFRAMES, hasGyroscope = true))
        assertTrue(PoseFreeRoom.ready(CoverageCells(), PoseFreeRoom.MIN_KEYFRAMES, hasGyroscope = false))
        assertTrue(PoseFreeRoom.ready(CoverageCells(), PoseFreeRoom.BUDGET, hasGyroscope = true))
        assertEquals(300, PoseFreeRoom.BUDGET)
        assertEquals(18, seen(18).count(CoverageBand.LEVEL))
    }

    @Test
    fun `YawTurns counts each further full turn either way, looking left and right never`() {
        val n = Coverage.SEGMENTS
        val t = YawTurns()
        assertFalse(t.add(0))
        // Sweeping a quarter left and right for a while is no turn.
        repeat(10) {
            for (k in 1..n / 4) assertFalse(t.add(k))
            for (k in n / 4 - 1 downTo 0) assertFalse(t.add(k))
        }
        // Backwards one full turn: the last step completes it.
        for (k in 1 until n) assertFalse(t.add((n - k) % n))
        assertTrue(t.add(0))
        // Then forwards a full turn from there: a further one.
        for (k in 1 until n) assertFalse(t.add(k))
        assertTrue(t.add(0))
        assertEquals(2, t.turns)
        t.reset()
        assertEquals(0, t.turns)
    }

    private companion object {
        fun done(): Job = Job().apply { complete() }
    }
}
