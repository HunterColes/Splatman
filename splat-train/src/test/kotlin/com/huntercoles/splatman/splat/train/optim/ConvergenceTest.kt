package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The ADR 0005 optimiser recovers a known scene (ADR 0009): 8 views (64 px, 0.5 rad field of view, so the cloud
 * fills the frame) of a seeded 50-splat cloud rendered by the forward pass are the targets; a perturbed copy
 * (positions +-5 cm, log-scales +-0.2, opacity logits +-0.5, colours +-0.5 SH, rotations +-0.05) must reach
 * PSNR > 28 dB over the 8 views within 500 iterations, under 60 s.
 * The run keeps the default schedule, so it densifies at iteration 300; `maxSplats` is the true count, so the cap
 * stops that from growing a cloud that already has every splat it needs (prune still runs).
 */
class ConvergenceTest {

    @Test
    fun `a perturbed cloud converges to the known one`() {
        val truth = TestScenes.cloud(50, seed = 42)
        val k = TestScenes.intrinsics(64, fov = 0.5f)
        val keyframes = TestScenes.keyframes(truth, TestScenes.ring(8, radius = 2f), k)
        val start = perturbed(truth, Random(5))
        val before = TestScenes.psnr(start, keyframes)

        val began = System.nanoTime()
        val optimizer = Rasterizer().use { raster ->
            SplatOptimizer(start, keyframes, 500, maxSplats = truth.count, rasterizer = raster, seed = 1L).also { opt ->
                repeat(500) { opt.step() }
            }
        }
        val seconds = (System.nanoTime() - began) / 1e9
        val after = TestScenes.psnr(optimizer.cloud, keyframes)
        println("ConvergenceTest: %.2f dB -> %.2f dB, %d splats, %.1f s".format(before, after, optimizer.cloud.count, seconds))

        assertTrue(before < 28.0, "the start must be off: %.2f dB".format(before))
        assertTrue(after > 28.0, "PSNR %.2f dB after 500 iterations (from %.2f dB)".format(after, before))
        assertTrue(seconds < 60.0, "took %.1f s".format(seconds))
    }

    private fun perturbed(c: SplatCloud, r: Random): SplatCloud {
        fun jitter(a: FloatArray, sigma: Double) = FloatArray(a.size) { (a[it] + sigma * r.nextGaussian()).toFloat() }
        return SplatCloud(
            c.count, c.shDegree,
            positions = jitter(c.positions, 0.05),
            logScales = jitter(c.logScales, 0.2),
            rotations = jitter(c.rotations, 0.05),
            opacityLogits = jitter(c.opacityLogits, 0.5),
            shDc = jitter(c.shDc, 0.5),
            shRest = c.shRest.copyOf(),
        )
    }
}
