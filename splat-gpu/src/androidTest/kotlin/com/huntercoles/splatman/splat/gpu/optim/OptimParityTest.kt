package com.huntercoles.splatman.splat.gpu.optim

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.GpuSupport
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.train.optim.Adam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * On-device parity of the optimiser kernels with the CPU (ADR 0010 tolerances): one Adam step on 100k seeded params
 * within 1e-6 relative, the L1 loss on 320x240 within 1e-5 relative (dL/dColor exact), the statistics exact.
 * Skips without GLES 3.1. QA: ./gradlew :splat-gpu:connectedDebugAndroidTest, then the `OptimParity` log lines.
 */
@RunWith(AndroidJUnit4::class)
class OptimParityTest {
    private var gl: GlesCompute? = null

    @Before
    fun setUp() {
        val support = GpuSupport.probe()
        assumeTrue("GPU unsupported: $support", support is GpuSupport.Supported)
        gl = GlesCompute.create()
    }

    @After
    fun tearDown() {
        gl?.close()
    }

    @Test
    fun adamStepMatchesCpuWithin1e6Relative() {
        val n = 100_000
        val rnd = Random(42)
        val start = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val cpuParams = start.copyOf()
        val cpu = Adam(1)
        var worst = 0.0
        gl!!.run {
            val params = floats(start)
            val grads = ssbo(Std430.arrayBytes(Std430Type.FLOAT, n))
            GpuAdam(this, 1).use { adam ->
                for (step in 1..3) {
                    val g = FloatArray(n) { rnd.nextFloat() * 0.2f - 0.1f }
                    val before = cpuParams.copyOf()
                    cpu.step(cpuParams, g, n, LR)
                    grads.upload(g)
                    adam.step(params, grads, n, LR)
                    val out = params.downloadFloats(n)
                    var stepWorst = 0.0
                    for (j in 0 until n) stepWorst = max(stepWorst, relative(out[j], cpuParams[j], before[j]))
                    if (step == 1) {
                        // After the first step the CPU's moments are (0.1 g).toFloat() and (0.001 g^2).toFloat().
                        val m = adam.firstMoments(n)
                        val v = adam.secondMoments(n)
                        for (j in 0 until n) {
                            val gd = g[j].toDouble()
                            stepWorst = max(stepWorst, relative(m[j], ((1 - Adam.BETA1) * gd).toFloat(), 0f))
                            stepWorst = max(stepWorst, relative(v[j], ((1 - Adam.BETA2) * gd * gd).toFloat(), 0f))
                        }
                    }
                    Log.i(TAG, "adam step $step: worst relative error $stepWorst over $n params")
                    worst = max(worst, stepWorst)
                }
                assertEquals(3, adam.steps)
            }
        }
        assertTrue("worst relative error $worst > 1e-6", worst <= 1e-6)
    }

    @Test
    fun l1LossMatchesCpuWithin1e5RelativeAndGradientExactly() {
        val w = 320
        val h = 240
        val size = 3 * w * h
        val rnd = Random(42)
        val rgba = ByteArray(4 * w * h).also { rnd.nextBytes(it) }
        val target = FloatArray(size) { j -> (rgba[4 * (j / 3) + j % 3].toInt() and 0xFF) / 255f }
        // Every seventh channel equals its target exactly, so the zero branch of the sign is exercised.
        val rendered = FloatArray(size) { j -> if (j % 7 == 0) target[j] else rnd.nextFloat() }
        val cpuGrad = FloatArray(size)
        val cpuLoss = cpuL1(rendered, target, cpuGrad)

        val (gpuLoss, gpuGrad) = gl!!.run {
            GpuTargets(this, listOf(RgbImage(4, 4, ByteArray(64)), RgbImage(w, h, rgba))).use { targets ->
                GpuLoss(this).use { loss ->
                    val r = floats(rendered)
                    val d = ssbo(Std430.arrayBytes(Std430Type.FLOAT, size))
                    loss.compute(r, targets[1], d)
                    val first = loss.loss()
                    loss.compute(r, targets[1], d)
                    assertEquals("the reduction is deterministic", first.toRawBits(), loss.loss().toRawBits())
                    first to d.downloadFloats(size)
                }
            }
        }
        val rel = abs(gpuLoss.toDouble() - cpuLoss) / cpuLoss
        var gradMismatches = 0
        for (j in 0 until size) if (gpuGrad[j].toRawBits() != cpuGrad[j].toRawBits()) gradMismatches++
        Log.i(TAG, "loss gpu $gpuLoss cpu $cpuLoss relative $rel; dLdColor mismatches $gradMismatches of $size")
        assertTrue("loss relative error $rel > 1e-5", rel <= 1e-5)
        assertEquals("dLdColor elements that differ", 0, gradMismatches)
    }

    @Test
    fun targetsAreUploadedPacked() {
        val rnd = Random(42)
        val images = listOf(
            RgbImage(3, 2, ByteArray(24).also { rnd.nextBytes(it) }),
            RgbImage(5, 1, ByteArray(20).also { rnd.nextBytes(it) }),
        )
        gl!!.run {
            GpuTargets(this, images).use { targets ->
                assertEquals(2, targets.size)
                for (i in images.indices) {
                    val t = targets[i]
                    assertEquals(images[i].width * images[i].height, t.pixels)
                    assertTrue(GpuTargets.pack(images[i].rgba).contentEquals(t.buffer.downloadInts(t.pixels)))
                }
            }
        }
    }

    @Test
    fun gradStatsMatchCpuExactly() {
        val n = 2000
        val rnd = Random(42)
        val sum = FloatArray(n) { rnd.nextFloat() * 10f }
        val views = IntArray(n) { rnd.nextInt(0, 50) }
        val cpuSum = sum.copyOf()
        val cpuViews = views.copyOf()
        val passes = List(2) {
            IntArray(n) { if (rnd.nextFloat() < 0.3f) 0 else rnd.nextInt(1, 40) } to FloatArray(n) { rnd.nextFloat() * 3f }
        }
        for ((radii, norms) in passes) {
            for (i in 0 until n) {
                if (radii[i] > 0) {
                    cpuSum[i] += norms[i]
                    cpuViews[i]++
                }
            }
        }
        val (gpuSum, gpuViews) = gl!!.run {
            val s = floats(sum)
            val v = ints(views)
            val radii = ssbo(Std430.arrayBytes(Std430Type.INT, n))
            val norms = ssbo(Std430.arrayBytes(Std430Type.FLOAT, n))
            GpuGradStats(this).use { stats ->
                for ((r, g) in passes) {
                    radii.upload(r)
                    norms.upload(g)
                    stats.accumulate(radii, norms, s, v, n)
                }
            }
            s.downloadFloats(n) to v.downloadInts(n)
        }
        var mismatches = 0
        for (i in 0 until n) {
            if (gpuSum[i].toRawBits() != cpuSum[i].toRawBits() || gpuViews[i] != cpuViews[i]) mismatches++
        }
        Log.i(TAG, "grad stats: $mismatches of $n splats differ")
        assertEquals(0, mismatches)
    }

    private companion object {
        const val TAG = "OptimParity"
        const val LR = 1e-3

        fun GlesCompute.floats(a: FloatArray): Ssbo =
            ssbo(Std430.arrayBytes(Std430Type.FLOAT, a.size)).also { it.upload(a) }

        fun GlesCompute.ints(a: IntArray): Ssbo =
            ssbo(Std430.arrayBytes(Std430Type.INT, a.size)).also { it.upload(a) }

        /** `CpuBackend.l1` (private there), line for line: the reference loss and its gradient. */
        fun cpuL1(rendered: FloatArray, target: FloatArray, dLdColor: FloatArray): Double {
            val size = target.size
            val scale = 1f / size
            var sum = 0.0
            for (j in 0 until size) {
                val d = rendered[j] - target[j]
                sum += abs(d)
                dLdColor[j] = when {
                    d > 0f -> scale
                    d < 0f -> -scale
                    else -> 0f
                }
            }
            return (sum / size).toFloat().toDouble()
        }

        /** |gpu - cpu| over max(|cpu|, |before|): a value stepped to near zero is judged against its step's scale. */
        fun relative(gpu: Float, cpu: Float, before: Float): Double {
            val d = abs(gpu.toDouble() - cpu)
            val scale = max(abs(cpu.toDouble()), abs(before.toDouble()))
            return if (scale == 0.0) d else d / scale
        }
    }
}
