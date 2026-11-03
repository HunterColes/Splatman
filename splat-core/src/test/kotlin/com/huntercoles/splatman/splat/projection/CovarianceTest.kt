package com.huntercoles.splatman.splat.projection

import com.huntercoles.splatman.splat.math.Mat3
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** ADR 0009: covariance kernels, 1e-4 relative. */
class CovarianceTest {

    private val fx = 500f
    private val fy = 480f

    private fun assertRel(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) <= 1e-4 * maxOf(abs(expected), 1e-6), "$what: expected $expected, was $actual")
    }

    private fun project(mean: Vec3, cov6: FloatArray, rot: Mat3 = Mat3.identity()): FloatArray? =
        Covariance.project(floatArrayOf(mean.x, mean.y, mean.z), 0, cov6, 0, rot, fx, fy, FloatArray(4))

    @Test
    fun `world covariance of an axis-aligned splat is diag(s^2)`() {
        val cov = Covariance.world(Vec3(ln(2f), ln(3f), ln(0.5f)), Quat.IDENTITY)
        val expected = floatArrayOf(4f, 0f, 0f, 9f, 0f, 0.25f)
        for (i in 0 until 6) assertEquals(expected[i], cov[i], 1e-5f, "index $i")
    }

    @Test
    fun `world covariance normalises the quaternion and equals R S S^T R^T`() {
        val q = Quat.fromAxisAngle(Vec3(1f, 2f, -0.5f), 0.9f)
        val scaled = Quat(q.w * 3f, q.x * 3f, q.y * 3f, q.z * 3f)
        val ls = Vec3(-1f, 0.2f, -2.5f)
        val cov = Covariance.world(ls, scaled)
        val r = q.toMat3()
        val s = doubleArrayOf(kotlin.math.exp(-1.0), kotlin.math.exp(0.2), kotlin.math.exp(-2.5))
        val idx = arrayOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 2)
        for ((n, rc) in idx.withIndex()) {
            var e = 0.0
            for (k in 0 until 3) e += r[rc.first, k].toDouble() * r[rc.second, k] * s[k] * s[k]
            assertEquals(e, cov[n].toDouble(), 1e-6, "entry $rc")
        }
    }

    @Test
    fun `isotropic splat at depth z with scale s has std fx s over z`() {
        for ((z, s) in listOf(1f to 0.05f, 2.5f to 0.1f, 10f to 0.3f)) {
            val cov = Covariance.world(Vec3(ln(s), ln(s), ln(s)), Quat.IDENTITY)
            val out = project(Vec3(0f, 0f, z), cov)!!
            val du = fx.toDouble() * s / z
            val dv = fy.toDouble() * s / z
            assertRel(du, sqrt(out[0] - 0.3), "u std at z=$z")
            assertRel(dv, sqrt(out[2] - 0.3), "v std at z=$z")
            assertEquals(0.0, out[1].toDouble(), 1e-6, "no u-v correlation on the axis")
            val lambda = maxOf(du * du, dv * dv) + 0.3
            assertRel(3 * sqrt(lambda), out[3].toDouble(), "3-sigma radius at z=$z")
        }
    }

    @Test
    fun `x-elongated splat turned 90 degrees about Z is elongated along v`() {
        val ls = Vec3(ln(0.5f), ln(0.02f), ln(0.02f))
        val straight = project(Vec3(0f, 0f, 3f), Covariance.world(ls, Quat.IDENTITY))!!
        assertTrue(straight[0] > 100 * straight[2], "unturned: long along u, ${straight.toList()}")
        val turned = project(Vec3(0f, 0f, 3f), Covariance.world(ls, Quat.fromAxisAngle(Vec3.UNIT_Z, (PI / 2).toFloat())))!!
        assertTrue(turned[2] > 100 * turned[0], "turned: long along v, ${turned.toList()}")
        assertRel(fy.toDouble() * 0.5 / 3, sqrt(turned[2] - 0.3), "v std of the turned splat")
    }

    @Test
    fun `world-to-camera rotation is applied`() {
        // Splat long along world Y; a camera rotated so world Y maps to Ccv X sees it long along u.
        val cov = Covariance.world(Vec3(ln(0.02f), ln(0.5f), ln(0.02f)), Quat.IDENTITY)
        val yToX = Mat3.fromRows(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val out = project(Vec3(0f, 0f, 3f), cov, yToX)!!
        assertTrue(out[0] > 100 * out[2], out.toList().toString())
    }

    @Test
    fun `projected covariance is symmetric positive definite for random splats`() {
        val rnd = Random(27)
        repeat(2000) {
            val ls = Vec3(rnd.nextFloat() * 6 - 5, rnd.nextFloat() * 6 - 5, rnd.nextFloat() * 6 - 5)
            val q = Quat(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            val rot = Quat.fromAxisAngle(Vec3(rnd.nextFloat(), rnd.nextFloat() - 0.5f, 0.3f), rnd.nextFloat() * 6f).toMat3()
            val z = 0.25f + rnd.nextFloat() * 20f
            val mean = Vec3((rnd.nextFloat() - 0.5f) * z, (rnd.nextFloat() - 0.5f) * z, z)
            val out = project(mean, Covariance.world(ls, q), rot)!!
            val (a, b, c) = Triple(out[0].toDouble(), out[1].toDouble(), out[2].toDouble())
            assertTrue(a > 0 && c > 0 && a * c - b * b > 0, "not SPD: ${out.toList()}")
            assertTrue(out[3] > 0f && out[3].isFinite(), "radius ${out[3]}")
        }
    }

    @Test
    fun `behind or too near the camera returns null and leaves out untouched`() {
        val cov = Covariance.world(Vec3(-2f, -2f, -2f), Quat.IDENTITY)
        val out = FloatArray(4) { 7f }
        for (z in floatArrayOf(-3f, 0f, 0.1f, 0.2f, Float.NaN)) {
            assertNull(Covariance.project(floatArrayOf(0f, 0f, z), 0, cov, 0, Mat3.identity(), fx, fy, out), "z=$z")
        }
        assertTrue(out.all { it == 7f })
        assertNotNull(project(Vec3(0f, 0f, 0.21f), cov))
    }

    @Test
    fun `project writes at the offsets and allocates nothing per call`() {
        val means = FloatArray(3 * 64) { i -> if (i % 3 == 2) 1f + i * 0.01f else (i % 5) * 0.1f }
        val covs = FloatArray(6 * 64)
        for (i in 0 until 64) {
            Covariance.world(floatArrayOf(-2f, -2.5f, -3f), 0, floatArrayOf(1f, 0.1f, 0.2f, 0.3f), 0, covs, i * 6)
        }
        val out = FloatArray(4 * 64)
        val rot = Mat3.identity()
        fun pass() {
            for (i in 0 until 64) Covariance.project(means, i * 3, covs, i * 6, rot, fx, fy, out, i * 4)
        }
        repeat(2000) { pass() } // warm up
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1000) { pass() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 64_000, "64k calls allocated $allocated bytes")
        val single = project(Vec3(means[3 * 10], means[3 * 10 + 1], means[3 * 10 + 2]), covs.copyOfRange(60, 66))!!
        for (k in 0 until 4) assertEquals(single[k], out[40 + k])
    }
}
