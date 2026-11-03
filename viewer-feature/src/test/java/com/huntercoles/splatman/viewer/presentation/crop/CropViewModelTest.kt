package com.huntercoles.splatman.viewer.presentation.crop

import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.edit.cropToBox
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.crop.store.CropStore
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** [CropViewModel] with a fake [CropStore] and test dispatchers (#185 Acceptance A1..A5, Hardening H1..H2). */
class CropViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val scan = SplatSource.Scan("scan-1")
    private val sample = SplatSource.Sample("samples/cactus-sh1.ply", "Cactus")
    private val document = SplatSource.Document("content://picked/1", "Picked")

    /** Records every call; [hasOriginal] turns true on apply and false on revert, like FileCropStore. */
    private class FakeCropStore(var original: Boolean = false) : CropStore {
        val applied = mutableListOf<Pair<SplatSource, SplatCloud>>()
        val reverted = mutableListOf<SplatSource>()
        val asked = mutableListOf<SplatSource>()
        var gate: CompletableDeferred<Unit>? = null
        var failApply: Throwable? = null
        var failRevert: Throwable? = null

        override suspend fun apply(source: SplatSource, cloud: SplatCloud) {
            applied += source to cloud
            gate?.await()
            failApply?.let { throw it }
            original = true
        }

        override suspend fun revert(source: SplatSource) {
            reverted += source
            failRevert?.let { throw it }
            original = false
        }

        override suspend fun hasOriginal(source: SplatSource): Boolean {
            require(source !is SplatSource.Document) { "FileCropStore refuses a document" }
            asked += source
            return original
        }
    }

    /** Holds every dispatched block until [drain], which runs them on a thread of its own. */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatched = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            queue += block
        }

        fun drain() {
            val worker = Thread({ while (true) (queue.poll() ?: break).run() }, "crop-default")
            worker.start()
            worker.join()
        }
    }

    private fun viewModel(
        store: CropStore,
        default: CoroutineDispatcher = dispatcher,
        io: CoroutineDispatcher = dispatcher,
    ) = CropViewModel(store, default, io)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.effectsOf(vm: CropViewModel): List<CropEffect> {
        val seen = mutableListOf<CropEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { seen += it } }
        return seen
    }

    /** Ready on [source] and in crop mode over [cloud]. */
    private fun TestScope.entered(store: FakeCropStore, cloud: SplatCloud = spread(), source: SplatSource = scan) =
        viewModel(store).also {
            it.onIntent(CropIntent.SourceReady(source))
            advanceUntilIdle()
            it.onIntent(CropIntent.Enter(cloud))
            advanceUntilIdle()
        }

    private fun editing(vm: CropViewModel) = assertInstanceOf(CropUiState.Editing::class.java, vm.uiState.value)

    @Test
    fun `A1 Enter fits the box with fitCentral`() = test {
        val cloud = spread()
        val vm = entered(FakeCropStore(), cloud)

        assertEquals(CropBox.fitCentral(cloud), editing(vm).box)
    }

    @Test
    fun `A1 MoveFace moves the face through moveFace and SelectFace selects it`() = test {
        val cloud = spread()
        val vm = entered(FakeCropStore(), cloud)

        vm.onIntent(CropIntent.MoveFace(BoxFace.Y_POS, -0.3f))
        vm.onIntent(CropIntent.SelectFace(BoxFace.Z_NEG))
        advanceUntilIdle()

        val state = editing(vm)
        assertEquals(CropBox.fitCentral(cloud).moveFace(BoxFace.Y_POS, -0.3f), state.box)
        assertEquals(BoxFace.Z_NEG, state.selected)
    }

    @Test
    fun `A1 ResetBox returns to the fit`() = test {
        val cloud = spread()
        val vm = entered(FakeCropStore(), cloud)

        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.4f))
        vm.onIntent(CropIntent.Nudge(BoxFace.Z_NEG, out = true))
        vm.onIntent(CropIntent.ResetBox)
        advanceUntilIdle()

        assertEquals(CropBox.fitCentral(cloud), editing(vm).box)
    }

    @Test
    fun `A1 Cancel leaves crop mode with nothing written`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        val effects = effectsOf(vm)

        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.4f))
        vm.onIntent(CropIntent.Cancel)
        advanceUntilIdle()

        assertEquals(CropUiState.Idle(offered = true, canRevert = false), vm.uiState.value)
        assertTrue(store.applied.isEmpty())
        assertTrue(store.reverted.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `A2 Nudge moves each face one 1 cm step in and out through moveFace`() = test {
        assertEquals(0.01f, CropViewModel.NUDGE_STEP_METRES)
        val cloud = spread()
        val fit = CropBox.fitCentral(cloud)
        for (face in BoxFace.entries) {
            for (out in listOf(true, false)) {
                val vm = entered(FakeCropStore(), cloud)
                vm.onIntent(CropIntent.Nudge(face, out))
                advanceUntilIdle()

                val step = if (out) CropViewModel.NUDGE_STEP_METRES else -CropViewModel.NUDGE_STEP_METRES
                assertEquals(fit.moveFace(face, step), editing(vm).box, "$face out=$out")
            }
        }
    }

    @Test
    fun `A3 Apply crops, calls the store once with the cropped cloud, reloads and leaves crop mode`() = test {
        val store = FakeCropStore()
        val cloud = spread()
        val vm = entered(store, cloud)
        val effects = effectsOf(vm)
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.5f))
        advanceUntilIdle()
        val box = editing(vm).box

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()

        val expected = cropToBox(cloud, box)
        val (source, written) = store.applied.single()
        assertEquals(scan, source)
        assertEquals(expected.count, written.count)
        assertArrayEquals(expected.positions, written.positions)
        assertEquals(listOf(CropEffect.ReloadSource), effects)
        assertInstanceOf(CropUiState.Idle::class.java, vm.uiState.value)
    }

    @Test
    fun `A3 a zero-splat result is refused with crop_empty and nothing is written`() = test {
        val store = FakeCropStore()
        val cloud = twoClusters()
        val vm = entered(store, cloud)
        val effects = effectsOf(vm)
        // x in [0, 0.5]: between the clusters at x = -1 and x = +1.
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_NEG, -1.0f))
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.5f))
        advanceUntilIdle()
        val box = editing(vm).box
        assertEquals(0, cropToBox(cloud, box).count)

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()

        val state = assertInstanceOf(CropUiState.Refused::class.java, vm.uiState.value)
        assertEquals(CropMessage.EMPTY, state.message)
        assertEquals(box, state.box)
        assertTrue(store.applied.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `A3 intents are ignored while applying`() = test {
        val store = FakeCropStore().apply { gate = CompletableDeferred() }
        val vm = entered(store)
        // Move the face in, so the crop cuts something (a crop that keeps every splat writes nothing, #186).
        vm.onIntent(CropIntent.MoveFace(BoxFace.Y_NEG, -0.2f))
        advanceUntilIdle()
        val box = editing(vm).box

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()
        assertInstanceOf(CropUiState.Applying::class.java, vm.uiState.value)
        val ignored = listOf(
            CropIntent.MoveFace(BoxFace.X_POS, -0.3f), CropIntent.Nudge(BoxFace.Z_POS, out = false),
            CropIntent.SelectFace(BoxFace.X_NEG), CropIntent.ResetBox, CropIntent.Cancel, CropIntent.Apply,
            CropIntent.Revert, CropIntent.Enter(spread()),
        )
        for (intent in ignored) {
            vm.onIntent(intent)
            advanceUntilIdle()
            val state = assertInstanceOf(CropUiState.Applying::class.java, vm.uiState.value, "$intent")
            assertEquals(box, state.box, "$intent")
        }
        assertEquals(1, store.applied.size)
        assertTrue(store.reverted.isEmpty())

        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.applied.size)
        assertInstanceOf(CropUiState.Idle::class.java, vm.uiState.value)
    }

    @Test
    fun `A3 a 1M-splat apply never runs cropToBox on the main dispatcher`() = test {
        val store = FakeCropStore()
        val default = GatedDispatcher()
        val cloud = grid(1_000_000)
        val vm = viewModel(store, default = default)
        vm.onIntent(CropIntent.SourceReady(scan))
        advanceUntilIdle()
        vm.onIntent(CropIntent.Enter(cloud))
        advanceUntilIdle()
        default.drain()
        advanceUntilIdle()
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.5f))
        advanceUntilIdle()
        val box = editing(vm).box
        val before = default.dispatched.get()

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()

        // Main has run dry, yet the crop is still parked on the default dispatcher: it never ran on main.
        assertTrue(default.dispatched.get() > before, "Apply dispatched nothing to the default dispatcher")
        assertTrue(store.applied.isEmpty(), "the store was called before the default dispatcher ran")
        assertInstanceOf(CropUiState.Applying::class.java, vm.uiState.value)

        default.drain()
        advanceUntilIdle()
        assertEquals(cropToBox(cloud, box).count, store.applied.single().second.count)
        assertInstanceOf(CropUiState.Idle::class.java, vm.uiState.value)
    }

    @Test
    fun `A4 Revert calls the store, reloads and canRevert follows hasOriginal`() = test {
        val store = FakeCropStore(original = true)
        val vm = viewModel(store)
        val effects = effectsOf(vm)
        vm.onIntent(CropIntent.SourceReady(scan))
        advanceUntilIdle()
        assertEquals(CropUiState.Idle(offered = true, canRevert = true), vm.uiState.value)

        vm.onIntent(CropIntent.Revert)
        advanceUntilIdle()

        assertEquals(listOf<SplatSource>(scan), store.reverted)
        assertEquals(listOf(CropEffect.ReloadSource), effects)
        assertFalse(vm.uiState.value.canRevert)
    }

    @Test
    fun `A4 canRevert is false before a crop, true after Apply and false after Revert`() = test {
        val store = FakeCropStore()
        val vm = entered(store, source = sample)
        assertFalse(vm.uiState.value.canRevert)

        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.5f))
        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()
        assertEquals(sample, store.applied.single().first)
        assertTrue(vm.uiState.value.canRevert)

        vm.onIntent(CropIntent.Revert)
        advanceUntilIdle()
        assertEquals(listOf<SplatSource>(sample), store.reverted)
        assertFalse(vm.uiState.value.canRevert)
    }

    @Test
    fun `A5 crop is not offered for a document`() = test {
        val store = FakeCropStore()
        val vm = viewModel(store)
        vm.onIntent(CropIntent.SourceReady(scan))
        advanceUntilIdle()
        assertEquals(CropUiState.Idle(offered = true, canRevert = false), vm.uiState.value)

        vm.onIntent(CropIntent.SourceReady(document))
        advanceUntilIdle()
        assertEquals(CropUiState.Idle(offered = false, canRevert = false), vm.uiState.value)

        vm.onIntent(CropIntent.Enter(spread()))
        vm.onIntent(CropIntent.Apply)
        vm.onIntent(CropIntent.Revert)
        advanceUntilIdle()
        assertEquals(CropUiState.Idle(offered = false, canRevert = false), vm.uiState.value)
        assertTrue(store.asked.none { it is SplatSource.Document })
        assertTrue(store.applied.isEmpty())
        assertTrue(store.reverted.isEmpty())
    }

    @Test
    fun `H1 process death mid-edit drops the edit`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.4f))
        advanceUntilIdle()
        editing(vm)

        // A recreated process builds a fresh ViewModel: there is no saved state to bring the box back.
        val reborn = viewModel(store)
        runCurrent()
        assertEquals(CropUiState.Idle(), reborn.uiState.value)
        assertTrue(store.applied.isEmpty())
    }

    @Test
    fun `H2 a store failure shows crop_error, keeps the box and the old splat`() = test {
        val store = FakeCropStore().apply { failApply = IOException("No space left on device") }
        val vm = entered(store)
        val effects = effectsOf(vm)
        vm.onIntent(CropIntent.MoveFace(BoxFace.X_POS, -0.5f))
        advanceUntilIdle()
        val box = editing(vm).box

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()

        val state = assertInstanceOf(CropUiState.Refused::class.java, vm.uiState.value)
        assertEquals(CropMessage.SAVE_ERROR, state.message)
        assertEquals(box, state.box)
        assertFalse(state.canRevert)
        assertTrue(effects.isEmpty())

        // The line goes when the box next changes.
        vm.onIntent(CropIntent.Nudge(BoxFace.X_POS, out = true))
        advanceUntilIdle()
        assertEquals(box.moveFace(BoxFace.X_POS, CropViewModel.NUDGE_STEP_METRES), editing(vm).box)
    }

    @Test
    fun `#186 Enter selects the Bottom face`() = test {
        val vm = entered(FakeCropStore())

        assertEquals(BoxFace.Y_NEG, editing(vm).selected)
    }

    @Test
    fun `#186 an Apply that keeps every splat writes nothing, leaves crop mode and says crop_nothing`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        val effects = effectsOf(vm)
        BoxFace.entries.forEach { vm.onIntent(CropIntent.MoveFace(it, 0.5f)) }
        advanceUntilIdle()

        vm.onIntent(CropIntent.Apply)
        advanceUntilIdle()

        assertTrue(store.applied.isEmpty())
        assertEquals(listOf(CropEffect.NothingCut), effects)
        assertEquals(CropUiState.Idle(offered = true, canRevert = false), vm.uiState.value)
    }

    @Test
    fun `#186 a failed Revert keeps the splat and says crop_revert_error`() = test {
        val store = FakeCropStore(original = true).apply { failRevert = IOException("busy") }
        val vm = viewModel(store)
        val effects = effectsOf(vm)
        vm.onIntent(CropIntent.SourceReady(scan))
        advanceUntilIdle()

        vm.onIntent(CropIntent.Revert)
        advanceUntilIdle()

        assertEquals(listOf(CropEffect.RevertFailed), effects)
        assertEquals(CropUiState.Idle(offered = true, canRevert = true), vm.uiState.value)
    }

    private companion object {
        /** Centres on a 5 x 5 x 5 lattice over [-1, 1] on every axis. */
        fun spread(): SplatCloud = grid(125)

        /** [n] centres on a cubic lattice over [-1, 1]^3, filled x first. */
        fun grid(n: Int): SplatCloud {
            val side = Math.round(Math.cbrt(n.toDouble())).toInt().coerceAtLeast(2)
            return cloudAt(
                FloatArray(3 * n) { j ->
                    val i = j / 3
                    val k = when (j % 3) {
                        0 -> i % side
                        1 -> (i / side) % side
                        else -> (i / (side * side)) % side
                    }
                    -1f + 2f * k / (side - 1)
                },
            )
        }

        /** Ten centres at x = -1 and ten at x = +1, y and z spread over [-1, 1]. */
        fun twoClusters(): SplatCloud = cloudAt(
            FloatArray(60) { j ->
                val i = j / 3
                when (j % 3) {
                    0 -> if (i < 10) -1f else 1f
                    1 -> -1f + 0.2f * (i % 10)
                    else -> 1f - 0.2f * (i % 10)
                }
            },
        )

        fun cloudAt(centres: FloatArray): SplatCloud {
            val n = centres.size / 3
            return SplatCloud(
                count = n,
                shDegree = 0,
                positions = centres,
                logScales = FloatArray(3 * n) { -2f },
                rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
                opacityLogits = FloatArray(n) { 1f },
                shDc = FloatArray(3 * n) { 0.25f },
                shRest = FloatArray(0),
            )
        }
    }
}
