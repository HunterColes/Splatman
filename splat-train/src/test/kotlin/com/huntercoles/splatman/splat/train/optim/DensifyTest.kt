package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/**
 * [Densifier] on hand-built clouds, extent 1 m: so small means a largest scale <= 1 cm (cloned), large one over it
 * (split), oversize over 10 cm (pruned), transparent under opacity 0.005 (pruned).
 */
class DensifyTest {

    private val settings = OptimSettings()
    private val extent = 1.0
    private val hot = 1e-3f
    private val cold = 1e-5f

    /** One splat per (x, scale m, opacity), identity rotation, colour = its index. */
    private fun cloud(vararg splats: Triple<Float, Float, Float>): SplatCloud {
        val n = splats.size
        return SplatCloud(
            n, 0,
            positions = FloatArray(3 * n) { if (it % 3 == 0) splats[it / 3].first else 0f },
            logScales = FloatArray(3 * n) { ln(splats[it / 3].second) },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n) { val o = splats[it].third; ln(o / (1 - o)) },
            shDc = FloatArray(3 * n) { (it / 3).toFloat() },
            shRest = FloatArray(0),
        )
    }

    private fun densify(c: SplatCloud, grads: FloatArray, maxSplats: Int = 100) =
        Densifier.densify(c, grads, extent, maxSplats, settings, Random(7))

    @Test
    fun `a small splat above the threshold is cloned`() {
        val c = cloud(Triple(0f, 0.005f, 0.5f), Triple(1f, 0.005f, 0.5f))
        val r = densify(c, floatArrayOf(hot, cold))
        assertEquals(3, r.cloud.count)
        assertEquals(1, r.cloned)
        assertEquals(0, r.split)
        assertArrayEquals(intArrayOf(0, 1, -1), r.source)
        // The clone is an exact copy of splat 0, appended after the kept splats.
        assertArrayEquals(c.positions.copyOfRange(0, 3), r.cloud.positions.copyOfRange(6, 9))
        assertArrayEquals(c.logScales.copyOfRange(0, 3), r.cloud.logScales.copyOfRange(6, 9))
        assertEquals(c.opacityLogits[0], r.cloud.opacityLogits[2])
        assertEquals(0f, r.cloud.shDc[6])
    }

    @Test
    fun `a large splat above the threshold is split into two smaller children`() {
        val c = cloud(Triple(0f, 0.05f, 0.5f), Triple(1f, 0.05f, 0.5f))
        val r = densify(c, floatArrayOf(cold, hot))
        assertEquals(3, r.cloud.count)
        assertEquals(0, r.cloned)
        assertEquals(1, r.split)
        assertArrayEquals(intArrayOf(0, -1, -1), r.source)
        val childScale = ln(0.05f) - ln(1.6f)
        for (j in 1..2) {
            assertEquals(1f, r.cloud.shDc[3 * j], "child $j comes from splat 1")
            for (a in 0 until 3) assertEquals(childScale, r.cloud.logScales[3 * j + a], 1e-6f)
            // Drawn from the parent's Gaussian (sigma 5 cm): near x = 1, not on it.
            assertTrue(abs(r.cloud.positions[3 * j] - 1f) < 0.3f)
        }
        assertTrue(r.cloud.positions[3] != r.cloud.positions[6], "children are drawn independently")
    }

    @Test
    fun `transparent and oversize splats are pruned`() {
        val c = cloud(Triple(0f, 0.005f, 0.004f), Triple(1f, 0.005f, 0.5f), Triple(2f, 0.2f, 0.5f))
        val r = densify(c, floatArrayOf(hot, cold, hot))
        assertEquals(1, r.cloud.count)
        assertEquals(2, r.pruned)
        assertEquals(0, r.cloned + r.split, "pruned splats are not densified")
        assertArrayEquals(intArrayOf(1), r.source)
        assertEquals(1f, r.cloud.shDc[0])
    }

    @Test
    fun `the cap takes the highest gradients first and never cuts the cloud`() {
        val c = cloud(
            Triple(0f, 0.005f, 0.5f), Triple(1f, 0.005f, 0.5f), Triple(2f, 0.05f, 0.5f), Triple(3f, 0.005f, 0.5f),
        )
        val grads = floatArrayOf(3e-4f, 5e-4f, 4e-4f, 2e-4f)
        val r = densify(c, grads, maxSplats = 6)
        assertEquals(6, r.cloud.count)
        // Budget 2: splat 1 (cloned) and splat 2 (split); splats 0 and 3 lose out.
        assertEquals(1, r.cloned)
        assertEquals(1, r.split)
        assertArrayEquals(intArrayOf(0, 1, 3, -1, -1, -1), r.source)
        assertEquals(1f, r.cloud.shDc[9])

        val full = densify(c, grads, maxSplats = 3)
        assertEquals(4, full.cloud.count, "a cloud over the cap is kept whole and does not grow")
        assertEquals(0, full.cloned + full.split)
    }

    @Test
    fun `everything pruned leaves an empty cloud`() {
        val c = cloud(Triple(0f, 0.005f, 0.001f))
        val r = densify(c, floatArrayOf(hot))
        assertEquals(0, r.cloud.count)
    }
}
