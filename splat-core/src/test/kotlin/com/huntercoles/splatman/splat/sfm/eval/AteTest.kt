package com.huntercoles.splatman.splat.sfm.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AteTest {
    /** Rotation from axis-angle, row-major. */
    private fun rot(ax: Double, ay: Double, az: Double, angle: Double): DoubleArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        val (x, y, z) = Triple(ax / n, ay / n, az / n)
        val c = cos(angle)
        val s = sin(angle)
        val k = 1 - c
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        )
    }

    private fun apply(p: TumPose, s: Double, r: DoubleArray, t: DoubleArray) = p.copy(
        tx = s * (r[0] * p.tx + r[1] * p.ty + r[2] * p.tz) + t[0],
        ty = s * (r[3] * p.tx + r[4] * p.ty + r[5] * p.tz) + t[1],
        tz = s * (r[6] * p.tx + r[7] * p.ty + r[8] * p.tz) + t[2],
    )

    private fun spiral(n: Int) = List(n) { i ->
        val a = i * 0.05
        TumPose(i * 0.033, 2 * cos(a), 1.5 * sin(a), 0.01 * i % 3.0 + 0.3 * sin(3 * a))
    }

    private fun pairs(est: List<TumPose>, gt: List<TumPose>) = est.zip(gt) { e, g -> Ate.Pair(e, g) }

    @Test
    fun `known Sim3 scores zero and is recovered`() {
        val est = spiral(500)
        val r = rot(0.3, -1.0, 0.5, 1.1)
        val t = doubleArrayOf(4.0, -2.0, 0.7)
        val gt = est.map { apply(it, 2.5, r, t) }
        val res = Ate.score(pairs(est, gt), Ate.Align.SIM3)
        assertEquals(0.0, res.rmse, 1e-9)
        assertEquals(0.0, res.max, 1e-9)
        assertEquals(2.5, res.scale, 1e-9)
        for (i in 0..8) assertEquals(r[i], res.rotation[i], 1e-9)
        for (i in 0..2) assertEquals(t[i], res.translation[i], 1e-9)
        assertEquals(500, res.pairs)
    }

    @Test
    fun `SE3 keeps scale one and leaves the scale error`() {
        val est = spiral(200)
        val gt = est.map { apply(it, 2.0, rot(0.0, 0.0, 1.0, 0.4), doubleArrayOf(1.0, 0.0, 0.0)) }
        val res = Ate.score(pairs(est, gt), Ate.Align.SE3)
        assertEquals(1.0, res.scale, 0.0)
        assertTrue(res.rmse > 0.5)
    }

    @Test
    fun `1 cm noise gives the analytic RMSE within 10 percent`() {
        val rng = java.util.Random(7)
        val gt = spiral(10_000)
        val sd = 0.01
        val est = gt.map {
            it.copy(
                tx = it.tx + sd * rng.nextGaussian(),
                ty = it.ty + sd * rng.nextGaussian(),
                tz = it.tz + sd * rng.nextGaussian(),
            )
        }
        val res = Ate.score(pairs(est, gt), Ate.Align.SE3)
        val expected = sd * sqrt(3.0)
        assertTrue(abs(res.rmse - expected) < 0.1 * expected, "rmse ${res.rmse} vs $expected")
        assertTrue(res.median <= res.max && res.mean <= res.rmse)
    }

    @Test
    fun `reflection is fixed to a proper rotation`() {
        // Planar trajectory: the cross-covariance is rank 2 and its SVD may come back with det(U)det(V) = -1.
        val est = List(50) { i -> TumPose(i.toDouble(), cos(i * 0.2) * (1 + i * 0.01), sin(i * 0.2) * 0.5, 0.0) }
        val r = rot(1.0, 1.0, 0.0, 0.8)
        val gt = est.map { apply(it, 1.0, r, doubleArrayOf(0.0, 1.0, 2.0)) }
        for (align in Ate.Align.values()) {
            val res = Ate.score(pairs(est, gt), align)
            assertEquals(1.0, Ate.det(res.rotation), 1e-9)
            assertEquals(0.0, res.rmse, 1e-9)
        }
        // A mirrored (z -> -z) non-planar target: the best proper rotation, never a reflection.
        val sp = spiral(100)
        val mirrored = sp.map { it.copy(tz = -it.tz) }
        val res = Ate.score(pairs(sp, mirrored), Ate.Align.SE3)
        assertEquals(1.0, Ate.det(res.rotation), 1e-9)
        assertTrue(res.rmse > 0.0)
    }

    @Test
    fun `fewer than three pairs throws`() {
        val p = spiral(2)
        assertThrows<IllegalArgumentException> { Ate.score(pairs(p, p)) }
    }

    @Test
    fun `collinear trajectory is rejected for SIM3 with a reason`() {
        val line = List(10) { i -> TumPose(i.toDouble(), i * 1.0, i * 2.0, i * 3.0) }
        val e = assertThrows<IllegalArgumentException> { Ate.score(pairs(line, line), Ate.Align.SIM3) }
        assertTrue(e.message!!.contains("collinear"))
    }

    @Test
    fun `associate pairs nearest timestamps within maxDt`() {
        val gt = List(10) { i -> TumPose(i * 0.1, i.toDouble(), 0.0, 0.0) }
        val est = listOf(
            TumPose(0.005, 0.0, 0.0, 0.0),
            TumPose(0.29, 0.0, 0.0, 0.0),
            TumPose(0.55, 0.0, 0.0, 0.0), // 0.05 from both neighbours: dropped
            TumPose(0.91, 0.0, 0.0, 0.0),
        )
        val pairs = Ate.associate(est, gt, maxDtS = 0.02)
        assertEquals(listOf(0.0, 0.3, 0.9), pairs.map { Math.round(it.gt.t * 10) / 10.0 })
        assertEquals(3, pairs.size)
    }

    @Test
    fun `associate uses each ground-truth pose once`() {
        val gt = listOf(TumPose(1.0, 0.0, 0.0, 0.0))
        val est = listOf(TumPose(0.99, 0.0, 0.0, 0.0), TumPose(1.01, 0.0, 0.0, 0.0))
        assertEquals(1, Ate.associate(est, gt).size)
    }

}
