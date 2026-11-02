package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs

/**
 * Ticket #10: the trainer learns one focal scale and one k1. The targets are 16 views (64 px, 1.2 rad field of view) of
 * a seeded 50-splat cloud rendered with the true pinhole, from two rings about it at 1.4 m and 2.6 m; the keyframes
 * then claim a focal length 5 % too long, as a wrong phone calibration would. Training from a perturbed copy (as
 * [ConvergenceTest]) with the lens learned must bring the effective focal length to within 1 % of the truth, keep k1
 * near its true 0 and beat the PSNR of the same run with the lens frozen.
 *
 * Two distances matter: from one orbit distance, a focal error is nearly the same image as a scaled scene (weak
 * perspective), and the lens can drift along that valley instead of finding the focal length.
 */
class LensRefinementTest {

    private val truth = TestScenes.cloud(50, seed = 42)
    private val trueK = TestScenes.intrinsics(64, fov = 1.2f)
    private val poses = TestScenes.ring(8, radius = 1.4f) + TestScenes.ring(8, radius = 2.6f)
    private val wrongK = trueK.copy(fx = trueK.fx * FOCAL_ERROR, fy = trueK.fy * FOCAL_ERROR)

    /** The true images, with the wrong intrinsics attached. */
    private val keyframes: List<Keyframe> =
        TestScenes.keyframes(truth, poses, trueK).map { it.copy(intrinsics = wrongK) }

    private val start = perturbed(truth, Random(5))

    private val refining = OptimSettings(cameraFreezeSteps = FREEZE)

    private fun run(settings: OptimSettings, iterations: Int = ITERATIONS): SplatOptimizer =
        Rasterizer().use { raster ->
            SplatOptimizer(
                start, keyframes, iterations, maxSplats = truth.count, rasterizer = raster, settings = settings,
                seed = 1L,
            ).also { opt -> repeat(iterations) { opt.step() } }
        }

    @Test
    fun `a 5 percent focal error is recovered to within 1 percent and PSNR beats the frozen run`() {
        val learned = run(refining)
        val frozen = run(refining.copy(refineCamera = false))
        val focalError = learned.lens.focalScale * FOCAL_ERROR - 1f
        val learnedPsnr = TestScenes.psnr(learned.cloud, keyframes, learned.lens)
        val frozenPsnr = TestScenes.psnr(frozen.cloud, keyframes)
        println(
            "LensRefinementTest: focal error 5 %% -> %.2f %% (scale %.4f, k1 %.4f); PSNR frozen %.2f dB, learned %.2f dB"
                .format(100 * focalError, learned.lens.focalScale, learned.lens.k1, frozenPsnr, learnedPsnr),
        )
        assertTrue(abs(focalError) < 0.01f, "effective focal off by ${100 * focalError} %")
        assertTrue(abs(learned.lens.k1) < 0.01f, "k1 drifted to ${learned.lens.k1}")
        assertTrue(learnedPsnr > frozenPsnr, "learned %.2f dB vs frozen %.2f dB".format(learnedPsnr, frozenPsnr))
        assertEquals(Lens.PINHOLE, frozen.lens)
    }

    @Test
    fun `with the flag off nothing changes, and a lens frozen past the end is the same run`() {
        val off = run(refining.copy(refineCamera = false), iterations = 60)
        val neverThawed = run(refining.copy(cameraFreezeSteps = 60), iterations = 60)
        assertEquals(Lens.PINHOLE, off.lens)
        assertEquals(Lens.PINHOLE, neverThawed.lens)
        assertArrayEquals(off.cloud.positions, neverThawed.cloud.positions)
        assertArrayEquals(off.cloud.shDc, neverThawed.cloud.shDc)
        assertEquals(off.loss, neverThawed.loss)
        // Once it thaws, the lens does move.
        assertNotEquals(Lens.PINHOLE, run(refining.copy(cameraFreezeSteps = 10), iterations = 60).lens)
    }

    @Test
    fun `the lens stays within its limits`() {
        val tight = run(
            refining.copy(cameraFreezeSteps = 0, focalScaleLr = 0.05, k1Lr = 0.05, focalScaleLimit = 0.02, k1Limit = 0.01),
            iterations = 80,
        )
        assertTrue(tight.lens.focalScale in 0.98f..1.02f, "focalScale ${tight.lens.focalScale}")
        assertTrue(tight.lens.k1 in -0.01f..0.01f, "k1 ${tight.lens.k1}")
        assertTrue(abs(tight.lens.focalScale - 1f) > 0.019f, "the 5 % error must push the scale to its limit")
    }

    @Test
    fun `settings reject a negative freeze and limits out of range`() {
        assertThrows(IllegalArgumentException::class.java) { OptimSettings(cameraFreezeSteps = -1) }
        assertThrows(IllegalArgumentException::class.java) { OptimSettings(focalScaleLimit = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { OptimSettings(k1Limit = -0.1) }
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

    private companion object {
        const val FOCAL_ERROR = 1.05f
        const val ITERATIONS = 1500
        const val FREEZE = 100
    }
}
