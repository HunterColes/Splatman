package com.huntercoles.splatman.splat.gpu.optim

import com.huntercoles.splatman.splat.train.optim.Adam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** The pure side of the optimiser kernels, and their fp32 arithmetic replayed on the JVM against the CPU reference. */
class OptimMathTest {
    @Test
    fun `pack puts r in the low byte and reads bytes unsigned`() {
        val rgba = byteArrayOf(1, 2, 3, 4, -1, 0, -128, 127)
        val packed = GpuTargets.pack(rgba)
        assertEquals(2, packed.size)
        assertEquals(0x04030201, packed[0])
        assertEquals(0x7F8000FF, packed[1])
    }

    @Test
    fun `pack rejects a partial pixel`() {
        assertThrows<IllegalArgumentException> { GpuTargets.pack(ByteArray(5)) }
    }

    @Test
    fun `the byte table is the CPU's target conversion bit for bit`() {
        val lut = GpuLoss.byteLut()
        assertEquals(256, lut.size)
        for (b in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            val cpu = (b.toByte().toInt() and 0xFF) / 255f
            assertEquals(cpu.toRawBits(), lut[b and 0xFF].toRawBits())
        }
    }

    @Test
    fun `loss scalars match the CPU`() {
        val size = 320 * 240 * 3
        assertEquals(1f / size, GpuLoss.gradScale(size))
        assertEquals(900, GpuLoss.partialGroups(size))
        assertEquals(1, GpuLoss.partialGroups(1))
        assertThrows<IllegalArgumentException> { GpuLoss.partialGroups(0) }
        assertEquals((123.25 / size).toFloat(), GpuLoss.meanOf(123.25f, size))
    }

    @Test
    fun `adam uniforms are computed in double and rounded once`() {
        val u = AdamUniforms.of(Adam.BETA1, Adam.BETA2, Adam.EPS, 1, 1e-3)
        assertEquals(0.001f, u.oneMinusBeta2)
        assertNotEquals(1f - 0.999f, u.oneMinusBeta2)
        assertEquals(0.1f, u.bc1)
        assertEquals(0.001f, u.bc2)
        assertEquals(1e-15f, u.eps)
        val u3 = AdamUniforms.of(Adam.BETA1, Adam.BETA2, Adam.EPS, 3, 1e-3)
        assertEquals((1.0 - 0.9 * 0.9 * 0.9).toFloat(), u3.bc1)
        assertThrows<IllegalArgumentException> { AdamUniforms.of(0.9, 0.999, 1e-15, 0, 1e-3) }
    }

    @Test
    fun `the shader's fp32 adam stays within 1e-6 of Adam step over three steps`() {
        val n = 100_000
        val rnd = Random(42)
        val cpuParams = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val gpuParams = cpuParams.copyOf()
        val m = FloatArray(n)
        val v = FloatArray(n)
        val cpu = Adam(1)
        var worst = 0.0
        for (step in 1..3) {
            val grads = FloatArray(n) { rnd.nextFloat() * 0.2f - 0.1f }
            val before = cpuParams.copyOf()
            cpu.step(cpuParams, grads, n, LR)
            shaderAdam(gpuParams, grads, m, v, AdamUniforms.of(Adam.BETA1, Adam.BETA2, Adam.EPS, step, LR))
            for (j in 0 until n) worst = max(worst, relative(gpuParams[j], cpuParams[j], before[j]))
        }
        assertTrue(worst <= 1e-6, "worst relative error $worst")
    }

    @Test
    fun `the two-pass fp32 loss stays within 1e-5 of the CPU's double sum`() {
        val size = 320 * 240 * 3
        val rnd = Random(42)
        val target = ByteArray(size / 3 * 4).also { rnd.nextBytes(it) }
        val lut = GpuLoss.byteLut()
        val packed = GpuTargets.pack(target)
        val rendered = FloatArray(size) { rnd.nextFloat() }
        var cpuSum = 0.0
        val diffs = FloatArray(size) { j ->
            val t = lut[(packed[j / 3] ushr (8 * (j % 3))) and 0xFF]
            abs(rendered[j] - t).also { cpuSum += it }
        }
        val groups = GpuLoss.partialGroups(size)
        val partials = FloatArray(groups) { g -> tree(FloatArray(256) { t -> diffs.getOrElse(256 * g + t) { 0f } }) }
        val total = tree(FloatArray(256) { t -> (t until groups step 256).fold(0f) { a, i -> a + partials[i] } })
        val gpu = GpuLoss.meanOf(total, size)
        val cpu = (cpuSum / size).toFloat()
        assertTrue(abs(gpu - cpu) / cpu <= 1e-5, "gpu $gpu cpu $cpu")
    }

    private companion object {
        const val LR = 1e-3

        /** [AdamShaders.ADAM], element by element in fp32. */
        fun shaderAdam(p: FloatArray, g: FloatArray, m: FloatArray, v: FloatArray, u: AdamUniforms) {
            for (j in p.indices) {
                val mj = u.beta1 * m[j] + u.oneMinusBeta1 * g[j]
                val vj = u.beta2 * v[j] + u.oneMinusBeta2 * (g[j] * g[j])
                m[j] = mj
                v[j] = vj
                p[j] = p[j] - u.lr * (mj / u.bc1) / (sqrt(vj / u.bc2) + u.eps)
            }
        }

        /** The shaders' shared-memory tree over 256 values. */
        fun tree(a: FloatArray): Float {
            var s = 128
            while (s > 0) {
                for (t in 0 until s) a[t] = a[t] + a[t + s]
                s = s shr 1
            }
            return a[0]
        }

        /** |gpu - cpu| over max(|cpu|, |before|): a value stepped to near zero is judged against its step's scale. */
        fun relative(gpu: Float, cpu: Float, before: Float): Double {
            val d = abs(gpu.toDouble() - cpu)
            val scale = max(abs(cpu.toDouble()), abs(before.toDouble()))
            return if (scale == 0.0) d else d / scale
        }
    }
}
