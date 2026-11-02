package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Ticket #100: the [TrainBackend] seam. The CPU path stays bit-identical to the loop before the seam: [GOLDEN_CRC] was
 * recorded on master before the refactor (300 iterations, seed 0, densifying at iteration 100 and learning the lens
 * from 151).
 */
class TrainBackendTest {

    private val truth = TestScenes.cloud(20, seed = 3)
    private val keyframes =
        TestScenes.keyframes(truth, TestScenes.ring(4, radius = 2f), TestScenes.intrinsics(32, fov = 0.5f))
    private val start = TestScenes.cloud(20, seed = 7)
    private val settings = OptimSettings(densifyFrom = 100, densifyEvery = 100, cameraFreezeSteps = 150)

    @Test
    fun `the CPU path matches the golden checksum recorded before the seam`() {
        val opt = Rasterizer(1).use { r ->
            SplatOptimizer(start, keyframes, 300, maxSplats = 1000, rasterizer = r, settings = settings, seed = 0L)
                .also { o -> repeat(300) { o.step() } }
        }
        assertEquals(40, opt.cloud.count)
        assertEquals(GOLDEN_CRC, TestScenes.checksum(opt.cloud))
    }

    @Test
    fun `the backend path with read-checks gives the same golden cloud`() {
        val opt = Rasterizer(1).use { r ->
            Delegating(CpuBackend(keyframes, r)).use { backend ->
                SplatOptimizer(start, keyframes, 300, maxSplats = 1000, backend = backend, settings = settings, seed = 0L)
                    .also { o -> repeat(300) { o.step() } }
                    .also { assertEquals(2, backend.loads, "init and one densify") }
            }
        }
        assertEquals(GOLDEN_CRC, TestScenes.checksum(opt.snapshot()))
        assertTrue(opt.lens != Lens.PINHOLE, "the lens was learned through the delegating backend")
    }

    @Test
    fun `CpuBackend load then read round-trips exactly`() {
        val r = Random(9)
        val cloud = TestScenes.cloud(7, seed = 11)
        val state = TrainState(
            cloud,
            TrainState.GROUP_WIDTHS.map { w -> FloatArray(w * cloud.count) { r.nextFloat() - 0.5f } },
            TrainState.GROUP_WIDTHS.map { w -> FloatArray(w * cloud.count) { r.nextFloat() } },
            17,
            FloatArray(cloud.count) { r.nextFloat() },
            IntArray(cloud.count) { r.nextInt(5) },
        )
        val back = Rasterizer(1).use { raster -> CpuBackend(keyframes, raster).use { it.load(state); it.read() } }
        assertCloudEquals(cloud, back.cloud)
        for (g in TrainState.GROUP_WIDTHS.indices) {
            assertArrayEquals(state.adamM[g], back.adamM[g], "adamM $g")
            assertArrayEquals(state.adamV[g], back.adamV[g], "adamV $g")
        }
        assertEquals(17, back.adamSteps)
        assertArrayEquals(state.gradSum, back.gradSum)
        assertArrayEquals(state.gradViews, back.gradViews)
    }

    @Test
    fun `CpuBackend load copies, so the caller's state is never written`() {
        val cloud = TestScenes.cloud(5, seed = 2)
        val before = TestScenes.checksum(cloud)
        Rasterizer(1).use { raster ->
            CpuBackend(keyframes, raster).use { b ->
                b.load(TrainState.fresh(cloud))
                b.step(0, StepRates(1e-2, 1e-2, 1e-2, 1e-2, 1e-2))
                assertTrue(TestScenes.checksum(b.read().cloud) != before, "the step moved the loaded cloud")
            }
        }
        assertEquals(before, TestScenes.checksum(cloud))
    }

    @Test
    fun `a backend that returns a NaN loss fails training at that iteration`() {
        val fake = Fake(nanLossAt = 3)
        val opt = SplatOptimizer(start, keyframes, 10, maxSplats = 1000, backend = fake, settings = settings)
        opt.step()
        opt.step()
        val e = assertThrows(TrainingFailedException::class.java) { opt.step() }
        assertEquals(3, e.iteration)
        assertTrue("loss" in e.reason, e.reason)
    }

    @Test
    fun `a non-finite state from a backend fails at the read`() {
        val fake = Fake(nanLossAt = Int.MAX_VALUE)
        val opt = SplatOptimizer(start, keyframes, 10, maxSplats = 1000, backend = fake, settings = settings)
        repeat(4) { opt.step() }
        fake.state.adamV[2][5] = Float.NaN
        val e = assertThrows(TrainingFailedException::class.java) { opt.snapshot() }
        assertEquals(4, e.iteration)
        assertTrue("adamV.rotations[5]" in e.reason, e.reason)
    }

    @Test
    fun `a backend that cannot learn the lens keeps the pinhole`() {
        val fake = Fake(nanLossAt = Int.MAX_VALUE)
        val opt = SplatOptimizer(
            start, keyframes, 10, maxSplats = 1000, backend = fake, settings = settings.copy(cameraFreezeSteps = 0),
        )
        repeat(10) { opt.step() }
        assertEquals(Lens.PINHOLE, opt.lens)
        assertEquals(10, fake.steps)
    }

    /** Forwards everything to [inner], but is not a [CpuBackend]: the optimiser takes its read-checked path. */
    private class Delegating(private val inner: CpuBackend) : TrainBackend {
        var loads = 0
        override fun load(state: TrainState) {
            loads++
            inner.load(state)
        }
        override fun step(keyframe: Int, rates: StepRates) = inner.step(keyframe, rates)
        override fun read() = inner.read()
        override val learnsLens get() = inner.learnsLens
        override fun setLens(lens: Lens?) = inner.setLens(lens)
        override fun lensGrad() = inner.lensGrad()
        override fun close() = inner.close()
    }

    /** Holds the loaded state as is and trains nothing; its loss is NaN at iteration [nanLossAt]. */
    private class Fake(private val nanLossAt: Int) : TrainBackend {
        lateinit var state: TrainState
        var steps = 0
        override fun load(state: TrainState) {
            this.state = state
        }
        override fun step(keyframe: Int, rates: StepRates): Float {
            steps++
            return if (steps == nanLossAt) Float.NaN else 0.5f
        }
        override fun read() = state
        override fun close() = Unit
    }

    private fun assertCloudEquals(a: SplatCloud, b: SplatCloud) {
        assertEquals(a.count, b.count)
        assertEquals(a.shDegree, b.shDegree)
        assertArrayEquals(a.positions, b.positions)
        assertArrayEquals(a.logScales, b.logScales)
        assertArrayEquals(a.rotations, b.rotations)
        assertArrayEquals(a.opacityLogits, b.opacityLogits)
        assertArrayEquals(a.shDc, b.shDc)
        assertArrayEquals(a.shRest, b.shRest)
    }

    private companion object {
        /** [TestScenes.checksum] of the 300-iteration run, recorded on master before the seam (40 splats). */
        const val GOLDEN_CRC = 0x20bf6932L
    }
}
