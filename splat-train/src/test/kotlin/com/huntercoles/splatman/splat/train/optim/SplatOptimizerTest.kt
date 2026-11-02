package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

/** [SplatOptimizer]'s schedule, determinism and Hardening cases (ADR 0005). */
class SplatOptimizerTest {

    private val k = TestScenes.intrinsics(32, fov = 0.5f)
    private val truth = TestScenes.cloud(20, seed = 3)
    private val keyframes = TestScenes.keyframes(truth, TestScenes.ring(4, radius = 2f), k)

    private fun optimizer(
        start: SplatCloud,
        raster: Rasterizer,
        iterations: Int = 50,
        settings: OptimSettings = OptimSettings(),
    ) = SplatOptimizer(start, keyframes, iterations, maxSplats = 1000, rasterizer = raster, settings = settings, seed = 1L)

    @Test
    fun `scene extent and position learning rate follow 3DGS`() {
        Rasterizer(1).use { raster ->
            val opt = optimizer(truth, raster, iterations = 3000)
            // Ring of radius 2 at heights +-0.3 about its mean centre: 1.1 * sqrt(4 + 0.09).
            assertEquals(1.1 * kotlin.math.sqrt(4.09), opt.extent, 1e-5)
            assertEquals(1.6e-4, opt.positionLr(0), 1e-12)
            assertEquals(1.6e-5, opt.positionLr(1500), 1e-12)
            assertEquals(1.6e-6, opt.positionLr(3000), 1e-13)
        }
    }

    @Test
    fun `densification runs every 100 iterations from 300 to 60 percent of the run`() {
        val s = OptimSettings()
        val at = (1..3000).filter { s.densifiesAt(it, 3000) }
        assertEquals((300..1800 step 100).toList(), at)
        assertEquals(emptyList<Int>(), (1..400).filter { s.densifiesAt(it, 400) })
    }

    @Test
    fun `the same seed gives the same cloud for any thread count`() {
        val a = Rasterizer(1).use { r -> optimizer(truth.shifted(), r).also { o -> repeat(20) { o.step() } }.cloud }
        val b = Rasterizer(4).use { r -> optimizer(truth.shifted(), r).also { o -> repeat(20) { o.step() } }.cloud }
        assertArrayEquals(a.positions, b.positions)
        assertArrayEquals(a.shDc, b.shDc)
    }

    @Test
    fun `loss falls`() {
        Rasterizer(1).use { raster ->
            val opt = optimizer(truth.shifted(), raster, iterations = 100)
            val first = opt.step()
            repeat(99) { opt.step() }
            assertTrue(opt.loss < first, "loss ${opt.loss} not below the first $first")
        }
    }

    @Test
    fun `a cloud pruned to nothing stops with the iteration, not a crash`() {
        // Every splat nearly transparent: no gradient reaches them and the first densification prunes them all.
        val transparent = truth.withOpacity(ln(0.001f / 0.999f))
        val settings = OptimSettings(densifyFrom = 5)
        Rasterizer(1).use { raster ->
            val opt = optimizer(transparent, raster, iterations = 20, settings = settings)
            val e = assertThrows(TrainingFailedException::class.java) { repeat(20) { opt.step() } }
            assertEquals(5, e.iteration)
            assertTrue(e.message!!.contains("pruned"), e.message)
        }
    }

    @Test
    fun `an empty starting cloud fails at iteration 0`() {
        val none = FloatArray(0)
        val empty = SplatCloud(0, 0, none, none, none, none, none, none)
        Rasterizer(1).use { raster ->
            val e = assertThrows(TrainingFailedException::class.java) { optimizer(empty, raster) }
            assertEquals(0, e.iteration)
        }
    }

    @Test
    fun `a NaN parameter aborts with the iteration that produced it`() {
        Rasterizer(1).use { raster ->
            val opt = optimizer(truth.shifted(), raster)
            repeat(5) { opt.step() }
            opt.cloud.logScales[7] = Float.NaN
            val e = assertThrows(TrainingFailedException::class.java) { opt.step() }
            assertEquals(6, e.iteration)
            assertTrue(e.message!!.contains("logScales[7]"), e.message)
        }
    }

    @Test
    fun `a NaN in the starting cloud fails at iteration 0`() {
        val bad = truth.shifted().also { it.shDc[2] = Float.NaN }
        Rasterizer(1).use { raster ->
            val e = assertThrows(TrainingFailedException::class.java) { optimizer(bad, raster) }
            assertEquals(0, e.iteration)
        }
    }

    private fun SplatCloud.shifted() = SplatCloud(
        count, shDegree, FloatArray(positions.size) { positions[it] + 0.02f }, logScales.copyOf(), rotations.copyOf(),
        opacityLogits.copyOf(), shDc.copyOf(), shRest.copyOf(),
    )

    private fun SplatCloud.withOpacity(logit: Float) = SplatCloud(
        count, shDegree, positions.copyOf(), logScales.copyOf(), rotations.copyOf(), FloatArray(count) { logit },
        shDc.copyOf(), shRest.copyOf(),
    )
}
