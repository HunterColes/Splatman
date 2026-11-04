package com.huntercoles.splatman.viewer.presentation.erase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.crop.store.CropPaths
import com.huntercoles.splatman.viewer.crop.store.CropStore
import com.huntercoles.splatman.viewer.crop.store.DiskCropFileOps
import com.huntercoles.splatman.viewer.crop.store.FileCropStore
import com.huntercoles.splatman.viewer.crop.store.ScanChangeListener
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * [EraseViewModel] with a fake [CropStore] (or the real [FileCropStore] on a temp dir) and test dispatchers
 * (#331 Acceptance A1..A5, Hardening H1..H2). The camera is the identity: a centre (x, y, z) lands on surface
 * pixel ((x + 1) * W / 2, (1 - y) * H / 2) of a [W] x [H] surface.
 */
class EraseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    @TempDir
    lateinit var filesDir: File

    private val scan = SplatSource.Scan("scan-1")
    private val sample = SplatSource.Sample("samples/cactus-sh1.ply", "Cactus")

    /** Records every call, like CropViewModelTest's; [failApply] makes `apply` throw after recording. */
    private class FakeCropStore : CropStore {
        val applied = mutableListOf<Pair<SplatSource, SplatCloud>>()
        val reverted = mutableListOf<SplatSource>()
        var failApply: Throwable? = null
        var original = false

        override suspend fun apply(source: SplatSource, cloud: SplatCloud) {
            applied += source to cloud
            failApply?.let { throw it }
            original = true
        }

        override suspend fun revert(source: SplatSource) {
            reverted += source
            original = false
        }

        override suspend fun hasOriginal(source: SplatSource): Boolean {
            require(source !is SplatSource.Document) { "FileCropStore refuses a document" }
            return original
        }
    }

    /** Holds every dispatched block until [drain], which runs them (and any they queue) on a thread of its own. */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatched = AtomicInteger()
        val pending: Int get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            queue += block
        }

        fun drain() {
            val worker = Thread({ while (true) (queue.poll() ?: break).run() }, "erase-default")
            worker.start()
            worker.join()
        }
    }

    private fun viewModel(store: CropStore, default: CoroutineDispatcher = dispatcher) =
        EraseViewModel(store, default, dispatcher)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.effectsOf(vm: EraseViewModel): List<EraseEffect> {
        val seen = mutableListOf<EraseEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { seen += it } }
        return seen
    }

    /** Runs the main queue and the gated default one until neither has work left. */
    private fun TestScope.pump(gate: GatedDispatcher?) {
        repeat(MAX_PUMPS) {
            advanceUntilIdle()
            if (gate == null || gate.pending == 0) return
            gate.drain()
        }
        error("passes never settled")
    }

    /** Ready on [source] and in erase mode over [cloud]. */
    private fun TestScope.entered(
        store: CropStore,
        cloud: SplatCloud = clumps(),
        source: SplatSource = scan,
        gate: GatedDispatcher? = null,
    ) = viewModel(store, gate ?: dispatcher).also {
        it.onIntent(EraseIntent.SourceReady(source))
        pump(gate)
        it.onIntent(EraseIntent.Enter(cloud))
        pump(gate)
    }

    /** One finger stroke through the surface [points] (x, y pairs), lifted at the end. */
    private fun TestScope.stroke(
        vm: EraseViewModel,
        vararg points: Float,
        radius: Float = RADIUS,
        gate: GatedDispatcher? = null,
    ) {
        vm.onIntent(EraseIntent.StrokeStart(IDENTITY, W, H, radius))
        for (k in points.indices step 2) vm.onIntent(EraseIntent.StrokeTo(points[k], points[k + 1]))
        vm.onIntent(EraseIntent.StrokeEnd)
        pump(gate)
    }

    private fun TestScope.paintClump(vm: EraseViewModel, col: Int, row: Int) = stroke(vm, px(col), py(row))

    @Test
    fun `A1 Apply is ignored while nothing is marked`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        val effects = effectsOf(vm)
        val state = assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
        assertEquals(0, state.marked)
        assertNotNull(state.mask)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertTrue(store.applied.isEmpty())
        assertTrue(effects.isEmpty())
        assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
    }

    @Test
    fun `A1 a stroke that hits nothing leaves Apply off`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        val effects = effectsOf(vm)

        stroke(vm, 100f, 2f, 190f, 2f)
        val state = assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
        assertEquals(0, state.marked)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertTrue(store.applied.isEmpty())
        assertTrue(effects.isEmpty())
        assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
    }

    @Test
    fun `A2 Undo clears only the last stroke, then is off until the next stroke`() = test {
        val vm = entered(FakeCropStore())

        paintClump(vm, 0, 2)
        assertEquals(CLUMP, vm.uiState.value.marked)
        assertTrue(vm.uiState.value.canUndo)
        paintClump(vm, 4, 2)
        assertEquals(2 * CLUMP, vm.uiState.value.marked)

        vm.onIntent(EraseIntent.Undo)
        advanceUntilIdle()
        val undone = assertInstanceOf(EraseUiState.Marked::class.java, vm.uiState.value)
        assertEquals(CLUMP, undone.marked)
        assertFalse(undone.canUndo)
        assertTrue(markedIn(undone, 0, 2))
        assertFalse(markedIn(undone, 4, 2))

        vm.onIntent(EraseIntent.Undo)
        advanceUntilIdle()
        assertEquals(CLUMP, vm.uiState.value.marked)
        assertTrue(markedIn(vm.uiState.value, 0, 2))

        paintClump(vm, 2, 2)
        assertTrue(vm.uiState.value.canUndo)
        assertEquals(2 * CLUMP, vm.uiState.value.marked)
    }

    @Test
    fun `A2 Undo of the only stroke goes back to nothing marked`() = test {
        val vm = entered(FakeCropStore())
        paintClump(vm, 1, 1)

        vm.onIntent(EraseIntent.Undo)
        advanceUntilIdle()

        val state = assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
        assertFalse(state.canUndo)
        assertEquals(0, state.mask.marked)
    }

    @Test
    fun `A2 RemoveIsolated is one stroke for Undo`() = test {
        val vm = entered(FakeCropStore(), clumps(withFloaters = true))
        paintClump(vm, 0, 0)
        assertEquals(CLUMP, vm.uiState.value.marked)

        vm.onIntent(EraseIntent.RemoveIsolated)
        advanceUntilIdle()
        val isolated = assertInstanceOf(EraseUiState.Marked::class.java, vm.uiState.value)
        assertEquals(CLUMP + FLOATERS, isolated.marked)
        assertTrue(isolated.canUndo)
        for (f in 0 until FLOATERS) assertTrue(isolated.mask.isMarked(CLUMPS * CLUMP + f))

        vm.onIntent(EraseIntent.Undo)
        advanceUntilIdle()
        val undone = vm.uiState.value
        assertEquals(CLUMP, undone.marked)
        assertFalse(undone.canUndo)
        assertTrue(markedIn(undone, 0, 0))
        for (f in 0 until FLOATERS) assertFalse(undone.mask!!.isMarked(CLUMPS * CLUMP + f))
    }

    @Test
    fun `A2 Undo waits for a pass in flight and clears all of that stroke`() = test {
        val gate = GatedDispatcher()
        val vm = entered(FakeCropStore(), gate = gate)
        paintClump(vm, 0, 0)
        pump(gate)

        vm.onIntent(EraseIntent.StrokeStart(IDENTITY, W, H, RADIUS))
        vm.onIntent(EraseIntent.StrokeTo(px(0), py(2)))
        vm.onIntent(EraseIntent.StrokeTo(px(1), py(2)))
        vm.onIntent(EraseIntent.StrokeEnd)
        vm.onIntent(EraseIntent.Undo)
        pump(gate)

        val state = vm.uiState.value
        assertEquals(CLUMP, state.marked)
        assertFalse(state.canUndo)
        assertTrue(markedIn(state, 0, 0))
        assertFalse(markedIn(state, 0, 2))
        assertFalse(markedIn(state, 1, 2))
    }

    @Test
    fun `A3 Apply writes once with the unmarked cloud and emits ReloadSource`() = test {
        val store = FakeCropStore()
        val cloud = clumps()
        val vm = entered(store, cloud)
        val effects = effectsOf(vm)
        paintClump(vm, 2, 2)
        paintClump(vm, 3, 4)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertEquals(1, store.applied.size)
        val (source, written) = store.applied.single()
        assertEquals(scan, source)
        val gone = clumpIndices(2, 2) + clumpIndices(3, 4)
        val keep = (0 until cloud.count).filter { it !in gone }
        assertEquals(keep.size, written.count)
        assertArrayEquals(
            FloatArray(3 * keep.size) { cloud.positions[3 * keep[it / 3] + it % 3] },
            written.positions,
        )
        assertEquals(listOf<EraseEffect>(EraseEffect.ReloadSource), effects)
        val idle = assertInstanceOf(EraseUiState.Idle::class.java, vm.uiState.value)
        assertNull(idle.mask)
    }

    @Test
    fun `A3 Apply on a scan calls the listener once through the crop store`() = test {
        val changed = mutableListOf<String>()
        val store = fileStore(changed)
        val vm = entered(store, clumps(), scan)
        val effects = effectsOf(vm)
        paintClump(vm, 1, 3)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertEquals(listOf(scan.scanId), changed)
        assertTrue(store.hasOriginal(scan))
        assertEquals(listOf<EraseEffect>(EraseEffect.ReloadSource), effects)
    }

    @Test
    fun `A3 Apply on a sample writes the crop file and never calls the listener`() = test {
        val changed = mutableListOf<String>()
        val store = fileStore(changed)
        val vm = entered(store, clumps(), sample)
        val effects = effectsOf(vm)
        paintClump(vm, 1, 3)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertTrue(changed.isEmpty())
        assertTrue(store.hasOriginal(sample))
        assertEquals(listOf<EraseEffect>(EraseEffect.ReloadSource), effects)
    }

    @Test
    fun `A3 every splat marked is refused and writes nothing`() = test {
        val store = FakeCropStore()
        val cloud = clumps()
        val vm = entered(store, cloud)
        val effects = effectsOf(vm)
        stroke(vm, 100f, 100f, radius = 1000f)
        assertEquals(cloud.count, vm.uiState.value.marked)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertTrue(store.applied.isEmpty())
        assertTrue(effects.isEmpty())
        val refused = assertInstanceOf(EraseUiState.Refused::class.java, vm.uiState.value)
        assertEquals(EraseMessage.ALL_MARKED, refused.message)
        assertEquals(cloud.count, refused.marked)
        assertNotNull(refused.mask)
    }

    @Test
    fun `A3 a store failure is refused with erase_error and keeps the marks`() = test {
        val store = FakeCropStore().apply { failApply = IOException("No space left on device") }
        val vm = entered(store)
        val effects = effectsOf(vm)
        paintClump(vm, 2, 2)

        vm.onIntent(EraseIntent.Apply)
        advanceUntilIdle()

        assertEquals(1, store.applied.size)
        assertTrue(effects.isEmpty())
        val refused = assertInstanceOf(EraseUiState.Refused::class.java, vm.uiState.value)
        assertEquals(EraseMessage.SAVE_ERROR, refused.message)
        assertEquals(CLUMP, refused.marked)
        assertTrue(markedIn(refused, 2, 2))
    }

    @Test
    fun `A4 Cancel drops the mask and writes nothing`() = test {
        val store = FakeCropStore()
        val vm = entered(store)
        val effects = effectsOf(vm)
        paintClump(vm, 2, 2)

        vm.onIntent(EraseIntent.Cancel)
        advanceUntilIdle()

        val idle = assertInstanceOf(EraseUiState.Idle::class.java, vm.uiState.value)
        assertTrue(idle.offered)
        assertNull(idle.mask)
        assertEquals(0, idle.marked)
        assertTrue(store.applied.isEmpty())
        assertTrue(store.reverted.isEmpty())
        assertTrue(effects.isEmpty())

        vm.onIntent(EraseIntent.Enter(clumps()))
        advanceUntilIdle()
        assertInstanceOf(EraseUiState.NothingMarked::class.java, vm.uiState.value)
    }

    @Test
    fun `A5 points that arrive during a pass are all tested by the next pass`() = test {
        val gate = GatedDispatcher()
        val vm = entered(FakeCropStore(), gate = gate)

        vm.onIntent(EraseIntent.StrokeStart(IDENTITY, W, H, RADIUS))
        vm.onIntent(EraseIntent.StrokeTo(px(0), py(2)))
        runCurrent()
        assertTrue(gate.pending > 0, "the first pass runs on the default dispatcher")
        vm.onIntent(EraseIntent.StrokeTo(px(1), py(2)))
        vm.onIntent(EraseIntent.StrokeTo(px(2), py(2)))
        runCurrent()
        assertInstanceOf(EraseUiState.Painting::class.java, vm.uiState.value)
        assertEquals(0, vm.uiState.value.mask!!.marked, "no pass ran on the main dispatcher")
        vm.onIntent(EraseIntent.StrokeEnd)
        pump(gate)

        val state = assertInstanceOf(EraseUiState.Marked::class.java, vm.uiState.value)
        assertEquals(3 * CLUMP, state.marked)
        for (col in 0..2) assertTrue(markedIn(state, col, 2), "clump $col of row 2")
        assertFalse(markedIn(state, 3, 2))
        assertFalse(markedIn(state, 0, 1))
    }

    @Test
    fun `H1 one mask the size of the cloud serves every stroke and Undo`() = test {
        val cloud = clumps()
        val vm = entered(FakeCropStore(), cloud)
        val mask = vm.uiState.value.mask!!
        assertEquals(cloud.count, mask.count)

        paintClump(vm, 0, 0)
        assertSame(mask, vm.uiState.value.mask)
        paintClump(vm, 1, 1)
        assertSame(mask, vm.uiState.value.mask)
        vm.onIntent(EraseIntent.Undo)
        advanceUntilIdle()
        assertSame(mask, vm.uiState.value.mask)
        vm.onIntent(EraseIntent.RemoveIsolated)
        advanceUntilIdle()
        assertSame(mask, vm.uiState.value.mask)
    }

    @Test
    fun `H2 the mask is released on Cancel, Apply and onCleared`() = test {
        val cancelled = entered(FakeCropStore())
        paintClump(cancelled, 0, 0)
        cancelled.onIntent(EraseIntent.Cancel)
        advanceUntilIdle()
        assertNull(cancelled.uiState.value.mask)

        val applied = entered(FakeCropStore())
        paintClump(applied, 0, 0)
        applied.onIntent(EraseIntent.Apply)
        advanceUntilIdle()
        assertNull(applied.uiState.value.mask)

        val owner = ViewModelStore()
        val store = FakeCropStore()
        val cleared = ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(store) as T
            },
        )[EraseViewModel::class.java]
        cleared.onIntent(EraseIntent.SourceReady(scan))
        advanceUntilIdle()
        cleared.onIntent(EraseIntent.Enter(clumps()))
        advanceUntilIdle()
        paintClump(cleared, 0, 0)
        assertNotNull(cleared.uiState.value.mask)
        owner.clear()
        advanceUntilIdle()
        assertNull(cleared.uiState.value.mask)
    }

    /** The real store on the temp `filesDir`, with the scan's `splat.ply` in place; [changed] records the listener. */
    private fun fileStore(changed: MutableList<String>): FileCropStore {
        val folder = File(File(filesDir, CropPaths.SCANS_DIR), scan.scanId).apply { mkdirs() }
        DiskCropFileOps.write(File(folder, SplatSource.Scan.SPLAT_FILE), clumps())
        return FileCropStore(filesDir, ScanChangeListener { changed += it }, dispatcher)
    }

    private fun markedIn(state: EraseUiState, col: Int, row: Int): Boolean {
        val mask = state.mask ?: return false
        return clumpIndices(col, row).all { mask.isMarked(it) }
    }

    private companion object {
        const val W = 200
        const val H = 200
        const val RADIUS = 8f
        const val SIDE = 5
        const val CLUMPS = SIDE * SIDE
        const val CLUMP = 5
        const val FLOATERS = 2
        const val MAX_PUMPS = 64

        val IDENTITY = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

        /** Clump centres sit at x, y in {-0.8, -0.4, 0, 0.4, 0.8}: 40 px apart on the 200 px surface. */
        fun coord(k: Int) = -0.8f + 0.4f * k
        fun px(col: Int) = (coord(col) + 1f) * W / 2f
        fun py(row: Int) = (1f - coord(row)) * H / 2f

        fun clumpIndices(col: Int, row: Int): Set<Int> {
            val c = row * SIDE + col
            return (c * CLUMP until (c + 1) * CLUMP).toSet()
        }

        /**
         * [CLUMPS] clumps of [CLUMP] centres within 0.1 mm of each other on the z = 0 plane (never isolated), then,
         * with [withFloaters], [FLOATERS] lone centres far off screen (isolated by ADR 0014 rule 8).
         */
        fun clumps(withFloaters: Boolean = false): SplatCloud {
            val n = CLUMPS * CLUMP + if (withFloaters) FLOATERS else 0
            val positions = FloatArray(3 * n)
            for (i in 0 until CLUMPS * CLUMP) {
                val c = i / CLUMP
                val j = i % CLUMP
                positions[3 * i] = coord(c % SIDE) + 1e-4f * j
                positions[3 * i + 1] = coord(c / SIDE) - 1e-4f * j
                positions[3 * i + 2] = 1e-4f * j
            }
            if (withFloaters) {
                val f = CLUMPS * CLUMP
                positions[3 * f] = 3f
                positions[3 * f + 1] = 3f
                positions[3 * (f + 1)] = -3f
                positions[3 * (f + 1) + 1] = 3f
                positions[3 * (f + 1) + 2] = 2f
            }
            return SplatCloud(
                count = n,
                shDegree = 0,
                positions = positions,
                logScales = FloatArray(3 * n) { -4f },
                rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
                opacityLogits = FloatArray(n) { 1f },
                shDc = FloatArray(3 * n) { 0.25f },
                shRest = FloatArray(0),
            )
        }
    }
}
