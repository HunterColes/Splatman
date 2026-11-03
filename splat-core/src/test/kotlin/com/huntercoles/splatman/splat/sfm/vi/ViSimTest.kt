package com.huntercoles.splatman.splat.sfm.vi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class ViSimTest {
    /** RK4 of `R' = R [w]x, v' = R f + g, p' = v` over [steps] samples from [from], two per step (k, k+1, k+2). */
    private fun integrate(sim: ViSim, from: Int, steps: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val s0 = sim.state(sim.imu[from].t)
        var r = s0.rGS; var v = s0.v; var p = s0.p
        fun deriv(r: DoubleArray, v: DoubleArray, w: DoubleArray, f: DoubleArray) =
            Triple(M3.mul(r, M3.skew(w)), M3.add(M3.mv(r, f), sim.gravity), v)
        var k = from
        while (k < from + steps) {
            val h = sim.imu[k + 2].t - sim.imu[k].t
            val a = sim.imu[k]; val m = sim.imu[k + 1]; val b = sim.imu[k + 2]
            val k1 = deriv(r, v, a.gyro, a.accel)
            val k2 = deriv(M3.add(r, M3.scale(k1.first, h / 2)), M3.add(v, M3.scale(k1.second, h / 2)), m.gyro, m.accel)
            val k3 = deriv(M3.add(r, M3.scale(k2.first, h / 2)), M3.add(v, M3.scale(k2.second, h / 2)), m.gyro, m.accel)
            val k4 = deriv(M3.add(r, M3.scale(k3.first, h)), M3.add(v, M3.scale(k3.second, h)), b.gyro, b.accel)
            fun step(y: DoubleArray, d1: DoubleArray, d2: DoubleArray, d3: DoubleArray, d4: DoubleArray) =
                DoubleArray(y.size) { y[it] + h / 6 * (d1[it] + 2 * d2[it] + 2 * d3[it] + d4[it]) }
            val v1 = v
            val v2 = M3.add(v, M3.scale(k1.second, h / 2))
            val v3 = M3.add(v, M3.scale(k2.second, h / 2))
            val v4 = M3.add(v, M3.scale(k3.second, h))
            p = step(p, v1, v2, v3, v4)
            r = step(r, k1.first, k2.first, k3.first, k4.first)
            v = step(v, k1.second, k2.second, k3.second, k4.second)
            k += 2
        }
        return Triple(r, v, p)
    }

    @ParameterizedTest
    @EnumSource(ViScenario::class)
    fun `noise-free gyro integrates to the trajectory rotation within 1e-6 rad over 1 s`(scenario: ViScenario) {
        val sim = ViSim(7, scenario)
        val from = 300 // t = 1.5 s: the STATIC window crosses the start of the move at 2 s
        val (r, _, _) = integrate(sim, from, 200)
        val err = M3.angleBetween(sim.state(sim.imu[from + 200].t).rGS, r)
        assertTrue(err < 1e-6, "$scenario rotation error $err rad")
    }

    @ParameterizedTest
    @EnumSource(ViScenario::class)
    fun `noise-free accel double-integrates to the trajectory position within 1e-4 m over 1 s`(scenario: ViScenario) {
        val sim = ViSim(7, scenario)
        val from = 300
        val (_, _, p) = integrate(sim, from, 200)
        val truth = sim.state(sim.imu[from + 200].t).p
        val err = M3.norm(M3.sub(p, truth))
        assertTrue(err < 1e-4, "$scenario position error $err m")
        // The test has teeth: the body really moved and turned over that second (except STATIC's still phase).
        if (scenario != ViScenario.STATIC) assertTrue(M3.norm(M3.sub(truth, sim.state(1.5).p)) > 0.05)
    }

    @ParameterizedTest
    @EnumSource(ViScenario::class)
    fun `noise-free samples are the analytic rate and specific force`(scenario: ViScenario) {
        val sim = ViSim(3, scenario)
        for (sample in sim.imu.filterIndexed { i, _ -> i % 97 == 0 }) {
            val s = sim.state(sample.t)
            assertArrayEquals(s.omegaS, sample.gyro, 0.0)
            assertArrayEquals(M3.mtv(s.rGS, M3.sub(s.a, sim.gravity)), sample.accel, 0.0)
        }
        assertEquals(scenario.duration, sim.imu.last().t, 1e-12)
        assertEquals(1.0 / 200, sim.imu[1].t - sim.imu[0].t, 1e-15)
    }

    @ParameterizedTest
    @EnumSource(ViScenario::class)
    fun `with td 0 and readout 0 points reproject through CameraIntrinsics exactly`(scenario: ViScenario) {
        val sim = ViSim(11, scenario)
        val k = sim.config.intrinsics
        var seen = 0
        for (kf in sim.keyframes) {
            // Camera pose built here from the body state and the extrinsics, not taken from the keyframe.
            val s = sim.state(kf.tCam)
            val rGC = M3.mul(s.rGS, sim.config.rSC)
            val pGC = M3.add(s.p, M3.mv(s.rGS, sim.config.pSC))
            assertTrue(M3.maxAbsDiff(rGC, kf.rGC) < 1e-15 && M3.maxAbsDiff(pGC, kf.pGC) < 1e-15)
            for (o in kf.observations) {
                val c = M3.mtv(rGC, M3.sub(sim.points[o.point], pGC))
                assertEquals(k.fx * c[0] / c[2] + k.cx, o.u, 1e-9)
                assertEquals(k.fy * c[1] / c[2] + k.cy, o.v, 1e-9)
                assertTrue(c[2] > 0 && o.u in 0.0..(k.width - 1.0) && o.v in 0.0..(k.height - 1.0))
                assertEquals(kf.tCam, o.tRow, 0.0)
                seen++
            }
        }
        val n = sim.keyframes.size
        assertTrue(seen > 10 * n, "$scenario: only $seen observations in $n keyframes")
    }

    @Test
    fun `td shifts the exposure to t_cam + td`() {
        val td = 0.02
        val sim = ViSim(5, ViScenario.ORBIT, ViSimConfig(td = td))
        val plain = ViSim(5, ViScenario.ORBIT)
        var moved = 0.0
        for (kf in sim.keyframes.take(50)) {
            val (r, p) = sim.cameraPose(kf.tCam + td)
            assertArrayEquals(r, kf.rGC, 0.0)
            assertArrayEquals(p, kf.pGC, 0.0)
            for (o in kf.observations) {
                val c = sim.project(sim.points[o.point], kf.tCam + td)
                assertEquals(c[0], o.u, 1e-9)
                assertEquals(c[1], o.v, 1e-9)
                assertEquals(kf.tCam + td, o.tRow, 0.0)
                val q = plain.project(sim.points[o.point], kf.tCam)
                moved = maxOf(moved, hypot(q[0] - o.u, q[1] - o.v))
            }
        }
        assertTrue(moved > 0.5, "a 20 ms offset should move some pixel by more than 0.5 px, got $moved")
    }

    @Test
    fun `rolling shutter exposes row v at t + td + (v - h over 2) readout over h`() {
        val td = -0.01
        val readout = 0.03
        val sim = ViSim(5, ViScenario.ROOM, ViSimConfig(td = td, readout = readout))
        val h = sim.config.intrinsics.height.toDouble()
        var spread = 0.0
        for (kf in sim.keyframes) {
            for (o in kf.observations) {
                assertEquals(kf.tCam + td + (o.v - h / 2) * readout / h, o.tRow, 1e-12)
                val c = sim.project(sim.points[o.point], o.tRow)
                assertEquals(c[0], o.u, 1e-9)
                assertEquals(c[1], o.v, 1e-9)
                spread = maxOf(spread, abs(o.tRow - kf.tCam - td))
            }
        }
        assertTrue(spread > 0.01, "top and bottom rows should be about readout / 2 off the centre, got $spread")
    }

    @Test
    fun `IMU white noise and pixel noise have the stated sizes, biases start where stated and walk`() {
        val noise = ImuNoise.PHONE
        val sim = ViSim(9, ViScenario.ROOM, ViSimConfig(noise = noise, pixelSigma = 0.7))
        fun std(x: List<Double>): Double {
            val m = x.average()
            return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
        }
        val gErr = sim.imu.map { it.gyro[0] - sim.state(it.t).omegaS[0] - it.gyroBias[0] }
        val aErr = sim.imu.map { s ->
            val f = M3.mtv(sim.state(s.t).rGS, M3.sub(sim.state(s.t).a, sim.gravity))
            s.accel[2] - f[2] - s.accelBias[2]
        }
        assertEquals(noise.gyroDensity * sqrt(200.0), std(gErr), 0.05 * noise.gyroDensity * sqrt(200.0))
        assertEquals(noise.accelDensity * sqrt(200.0), std(aErr), 0.05 * noise.accelDensity * sqrt(200.0))
        assertArrayEquals(noise.gyroBias0, sim.imu.first().gyroBias, 0.0)
        assertArrayEquals(noise.accelBias0, sim.imu.first().accelBias, 0.0)
        assertTrue(M3.norm(M3.sub(sim.imu.last().accelBias, noise.accelBias0)) > 1e-4)
        val px = sim.keyframes.flatMap { kf -> kf.observations.map { it.u - it.uClean } }
        assertEquals(0.7, std(px), 0.05)
    }

    @Test
    fun `same seed gives the same scene, another seed another`() {
        val cfg = ViSimConfig(noise = ImuNoise.PHONE, pixelSigma = 1.0, td = 0.005, readout = 0.02)
        val a = ViSim(42, ViScenario.STATIC, cfg)
        val b = ViSim(42, ViScenario.STATIC, cfg)
        val c = ViSim(43, ViScenario.STATIC, cfg)
        assertEquals(a.imu.size, b.imu.size)
        a.imu.indices.forEach {
            assertArrayEquals(a.imu[it].gyro, b.imu[it].gyro, 0.0)
            assertArrayEquals(a.imu[it].accel, b.imu[it].accel, 0.0)
        }
        a.points.indices.forEach { assertArrayEquals(a.points[it], b.points[it], 0.0) }
        a.keyframes.indices.forEach { i ->
            assertEquals(a.keyframes[i].observations.map { it.u }, b.keyframes[i].observations.map { it.u })
        }
        assertTrue(a.imu[100].gyro[0] != c.imu[100].gyro[0] && a.points[0][1] != c.points[0][1])
    }

    @Test
    fun `scenarios have their stated shapes`() {
        val orbit = ViSim(1, ViScenario.ORBIT)
        val room = ViSim(1, ViScenario.ROOM)
        val still = ViSim(1, ViScenario.STATIC)
        for (t in listOf(0.0, 7.3, 15.0, 29.9)) {
            val p = orbit.state(t).p
            assertEquals(0.5, hypot(p[0], p[1]), 0.05)
            val q = room.state(t).p
            assertTrue(abs(q[0]) < 2 && abs(q[1]) < 1.5 && q[2] in 0.0..2.5)
        }
        for (t in listOf(0.0, 1.0, 1.99)) {
            val s = still.state(t)
            assertEquals(0.0, M3.norm(s.v) + M3.norm(s.a) + M3.norm(s.omegaS), 0.0)
        }
        assertEquals(1.0, still.state(5.5).p[1], 0.0)
        assertTrue(M3.norm(still.state(3.5).v) > 0.1)
        assertEquals(ViScenario.ORBIT.duration, 30.0, 0.0)
    }

    @Test
    fun `central differences reproduce a known Jacobian`() {
        val f = { x: DoubleArray -> doubleArrayOf(x[0] * x[1], sin(x[2]) * x[0], x[1] * x[1]) }
        val x = doubleArrayOf(0.7, -1.3, 0.4)
        val analytic = arrayOf(
            doubleArrayOf(x[1], x[0], 0.0),
            doubleArrayOf(sin(x[2]), 0.0, x[0] * cos(x[2])),
            doubleArrayOf(0.0, 2 * x[1], 0.0),
        )
        val before = x.copyOf()
        assertJacobian(analytic, NumericJacobian.central(f, x))
        assertArrayEquals(before, x, 0.0)
    }

    @Test
    fun `right-perturbed rotation Jacobian of R p is -R skew(p)`() {
        val r = M3.exp(doubleArrayOf(0.3, -0.8, 1.1))
        val p = doubleArrayOf(0.4, 2.0, -1.5)
        val numeric = NumericJacobian.rotationRight({ rr -> M3.mv(rr, p) }, r)
        val a = M3.scale(M3.mul(r, M3.skew(p)), -1.0)
        assertJacobian(Array(3) { i -> DoubleArray(3) { j -> a[3 * i + j] } }, numeric)
    }

    @Test
    fun `assertJacobian rejects a wrong entry and a wrong shape`() {
        val good = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(0.0, -3.0))
        fun m(vararg rows: DoubleArray) = arrayOf(*rows)
        assertJacobian(good, m(doubleArrayOf(1.00005, 2.0), doubleArrayOf(1e-8, -3.0)))
        assertThrows<AssertionError> { assertJacobian(good, m(doubleArrayOf(1.001, 2.0), doubleArrayOf(0.0, -3.0))) }
        assertThrows<AssertionError> { assertJacobian(good, m(doubleArrayOf(1.0, 2.0))) }
        val wide = m(doubleArrayOf(1.0, 2.0), doubleArrayOf(0.0, -3.0, 0.0))
        assertThrows<AssertionError> { assertJacobian(good, wide) }
        val nan = m(doubleArrayOf(1.0, Double.NaN), doubleArrayOf(0.0, -3.0))
        assertThrows<AssertionError> { assertJacobian(good, nan) }
    }
}
