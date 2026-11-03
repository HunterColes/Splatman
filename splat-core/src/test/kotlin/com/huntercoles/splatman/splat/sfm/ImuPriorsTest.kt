package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI

/**
 * #223: ImuPriors. `relativeRotation` returns `R_Ccv(t1)_Ccv(t0)` (maps Ccv(t0) coordinates to Ccv(t1)). The gyro
 * reads the body rate w, so `R_W_C(t1) = R_W_C(t0) exp(w dt)` and `R_C1_C0 = exp(-w dt)`: a positive rate about an
 * axis gives a rotation of minus the integrated angle about that axis (taken into Ccv by `rotationCcvImu`).
 * Tolerance 1e-4 rad (card). Pair indices i, j are positions in `frames` (here equal to SfmFrame.index).
 */
class ImuPriorsTest {

    private val tol = 1e-4
    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)

    /** The expected `R_C1_C0` for a total body rotation of [angleRad] about [axisCv] (Ccv). */
    private fun expected(axisCv: Vec3, angleRad: Double) = Quat.fromAxisAngle(axisCv, (-angleRad).toFloat())

    private fun frames(n: Int, spacingNs: Long) = List(n) { SfmFrame(it, it * spacingNs, "f$it.jpg", k) }

    @Test
    fun `A1 constant rate about one axis gives the exact angle, ends interpolated`() {
        val gyro = imuAt(times(0, 1000 * MS, 5 * MS)) { Vec3(0.5f, 0f, 0f) }
        val t0 = 3_300_000L // between samples at both ends
        val t1 = 803_700_000L
        val q = ImuPriors.relativeRotation(gyro, Quat.IDENTITY, t0, t1)
        val angle = 0.5 * (t1 - t0) / 1e9
        assertTrue(quatAngle(expected(Vec3.UNIT_X, angle), q) < tol, "got $q for $angle rad about -X")
    }

    @Test
    fun `A1 the rate is taken from IMU into Ccv by rotationCcvImu`() {
        val gyro = imuAt(times(0, 1000 * MS, 5 * MS)) { Vec3(0f, 0f, 0.7f) } // about IMU z
        val rotationCcvImu = Quat.fromAxisAngle(Vec3.UNIT_X, (PI / 2).toFloat()) // IMU z -> Ccv -y
        val q = ImuPriors.relativeRotation(gyro, rotationCcvImu, 100 * MS, 600 * MS)
        val axisCv = rotationCcvImu.rotate(Vec3.UNIT_Z)
        assertTrue(quatAngle(expected(axisCv, 0.7 * 0.5), q) < tol, "got $q")
    }

    @Test
    fun `A1 a linearly ramping rate integrates exactly (midpoint rule)`() {
        // rate(t) = 0.2 + 1.5 t rad/s about Y (t in s); angle over [t0, t1] = 0.2 dt + 0.75 (t1^2 - t0^2)
        val gyro = imuAt(times(0, 1000 * MS, 5 * MS)) { t -> Vec3(0f, (0.2 + 1.5 * t / 1e9).toFloat(), 0f) }
        val t0 = 52_500_000L
        val t1 = 947_100_000L
        val s0 = t0 / 1e9
        val s1 = t1 / 1e9
        val angle = 0.2 * (s1 - s0) + 0.75 * (s1 * s1 - s0 * s0)
        val q = ImuPriors.relativeRotation(gyro, Quat.IDENTITY, t0, t1)
        assertTrue(quatAngle(expected(Vec3.UNIT_Y, angle), q) < tol, "got $q for $angle rad")
    }

    @Test
    fun `A1 a gap in samples over 50 ms throws, a 40 ms gap does not`() {
        val before = times(0, 300 * MS, 5 * MS)
        val wide = imuAt(before + times(360 * MS, 1000 * MS, 5 * MS)) { Vec3(0.5f, 0f, 0f) }
        assertThrows<IllegalArgumentException> { ImuPriors.relativeRotation(wide, Quat.IDENTITY, 100 * MS, 900 * MS) }

        val narrow = imuAt(before + times(340 * MS, 1000 * MS, 5 * MS)) { Vec3(0.5f, 0f, 0f) }
        val q = ImuPriors.relativeRotation(narrow, Quat.IDENTITY, 100 * MS, 900 * MS)
        assertTrue(quatAngle(expected(Vec3.UNIT_X, 0.5 * 0.8), q) < tol, "got $q")
    }

    @Test
    fun `A2 every pair within the match window, no rotation without the gyro prior`() {
        val gyro = imuAt(times(0, 600 * MS, 5 * MS)) { Vec3.ZERO }
        val options = SfmOptions(matchWindow = 2, useGyroPrior = false)
        val pairs = ImuPriors.pairs(frames(6, 100 * MS), gyro, Quat.IDENTITY, options)
        val expectedIj = setOf(0 to 1, 0 to 2, 1 to 2, 1 to 3, 2 to 3, 2 to 4, 3 to 4, 3 to 5, 4 to 5)
        assertEquals(expectedIj, pairs.map { it.i to it.j }.toSet())
        assertEquals(expectedIj.size, pairs.size, "no duplicate pairs")
        pairs.forEach { assertNull(it.rotationCjCi, "pair ${it.i},${it.j}") }
    }

    @Test
    fun `A2 with the gyro prior every pair carries R_Cj_Ci`() {
        val rotationCcvImu = Quat.fromAxisAngle(Vec3(1f, 2f, -1f), 1.1f)
        val gyro = imuAt(times(0, 600 * MS, 5 * MS)) { Vec3(0f, 0.4f, 0f) }
        val options = SfmOptions(matchWindow = 8, useGyroPrior = true)
        val pairs = ImuPriors.pairs(frames(6, 100 * MS), gyro, rotationCcvImu, options)
        assertEquals(15, pairs.size, "all pairs of 6 frames")
        val axisCv = rotationCcvImu.rotate(Vec3.UNIT_Y)
        for (p in pairs) {
            val r = requireNotNull(p.rotationCjCi) { "pair ${p.i},${p.j} has no prior" }
            val angle = 0.4 * (p.j - p.i) * 0.1
            assertTrue(quatAngle(expected(axisCv, angle), r) < tol, "pair ${p.i},${p.j}: $r")
        }
    }

    @Test
    fun `A2 pairs whose predicted rotation exceeds 60 degrees are dropped`() {
        // 1 rad/s, frames 0.5 s apart: 28.6 deg (gap 1) and 57.3 deg (gap 2) kept, 85.9 deg (gap 3) dropped
        val gyro = imuAt(times(0, 2100 * MS, 5 * MS)) { Vec3(0f, 1f, 0f) }
        val options = SfmOptions(matchWindow = 3, useGyroPrior = true)
        val pairs = ImuPriors.pairs(frames(5, 500 * MS), gyro, Quat.IDENTITY, options)
        val expectedIj = setOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 2, 1 to 3, 2 to 4)
        assertEquals(expectedIj, pairs.map { it.i to it.j }.toSet())
        for (p in pairs) {
            val r = requireNotNull(p.rotationCjCi) { "pair ${p.i},${p.j} has no prior" }
            assertTrue(quatAngle(expected(Vec3.UNIT_Y, 0.5 * (p.j - p.i)), r) < tol, "pair ${p.i},${p.j}: $r")
        }
    }
}
