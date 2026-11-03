package com.huntercoles.splatman.splat.sfm.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class ImuPreintegrationTest {
    private val zero = DoubleArray(3)
    private val gravity = doubleArrayOf(0.0, 0.0, -ViSim.GRAVITY)

    /** Noise-free ORBIT whose IMU carries the PHONE turn-on biases, held constant. */
    private val biased by lazy {
        val b = ImuNoise.PHONE
        ViSim(7, ViScenario.ORBIT, ViSimConfig(noise = ImuNoise(0.0, 0.0, 0.0, 0.0, b.gyroBias0, b.accelBias0)))
    }
    private val sim200 by lazy { ViSim(7, ViScenario.ORBIT) }

    private fun mean(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { (a[it] + b[it]) / 2 }

    /** Samples [from] .. from + [steps], each step with the mean of the two samples that bracket it. */
    private fun window(
        sim: ViSim,
        from: Int,
        steps: Int,
        bg: DoubleArray = zero,
        ba: DoubleArray = zero,
        noise: ImuNoise = ImuNoise.NONE,
    ) = ImuPreintegration(bg, ba, noise).apply {
        for (k in from until from + steps) {
            val a = sim.imu[k]
            val b = sim.imu[k + 1]
            integrate(b.t - a.t, mean(a.gyro, b.gyro), mean(a.accel, b.accel))
        }
    }

    private fun trueState(sim: ViSim, k: Int, bg: DoubleArray = zero, ba: DoubleArray = zero): NavState {
        val s = sim.state(sim.imu[k].t)
        return NavState(s.rGS, s.p, s.v, bg, ba)
    }

    private fun maxResidual(sim: ViSim, from: Int, steps: Int): Double {
        val pre = window(sim, from, steps)
        return pre.residual(trueState(sim, from), trueState(sim, from + steps), gravity).maxOf { abs(it) }
    }

    private fun rows(m: DoubleArray) = Array(3) { i -> m.copyOfRange(3 * i, 3 * i + 3) }

    @Test
    fun `noise-free ORBIT 0_1 s windows at 200 Hz give zero residual within 1e-6 between true states`() {
        for (from in 0 until 5800 step 370) {
            val r = maxResidual(sim200, from, 20)
            assertTrue(r < 1e-6, "window from sample $from: max |residual| $r")
        }
    }

    @Test
    fun `noise-free ORBIT 1 s windows give zero residual within 1e-6 at 1000 Hz, and the error is second order`() {
        // Midpoint is second order: at 200 Hz a 1 s window keeps a few 1e-6 of discretisation error, 25 times what
        // 1000 Hz keeps. The 1e-6 check runs at 1000 Hz; the ratio pins the order.
        val sim1000 = ViSim(7, ViScenario.ORBIT, ViSimConfig(imuRate = 1000.0))
        var worst200 = 0.0
        var worst1000 = 0.0
        for (start in 0 until 29) {
            val r1000 = maxResidual(sim1000, start * 1000, 1000)
            assertTrue(r1000 < 1e-6, "1 s window from $start s at 1000 Hz: max |residual| $r1000")
            worst1000 = maxOf(worst1000, r1000)
            worst200 = maxOf(worst200, maxResidual(sim200, start * 200, 200))
        }
        assertTrue(worst200 < 2e-5, "1 s at 200 Hz: $worst200")
        assertTrue(worst200 / worst1000 > 15, "error ratio 200 Hz / 1000 Hz ${worst200 / worst1000}, want about 25")
    }

    @Test
    fun `corrected(true bias) matches re-integration with the true bias within 1e-5`() {
        val bg = ImuNoise.PHONE.gyroBias0
        val ba = ImuNoise.PHONE.accelBias0
        for (from in 0 until 5800 step 170) {
            val c = window(biased, from, 20).corrected(bg, ba)
            val exact = window(biased, from, 20, bg, ba)
            val dr = M3.angleBetween(c.deltaR, exact.deltaR)
            val dv = M3.maxAbsDiff(c.deltaV, exact.deltaV)
            val dp = M3.maxAbsDiff(c.deltaP, exact.deltaP)
            assertTrue(dr < 1e-5 && dv < 1e-5 && dp < 1e-5, "window from $from: rotation $dr, v $dv, p $dp")
            val r = exact.residual(trueState(biased, from, bg, ba), trueState(biased, from + 20, bg, ba), gravity)
            assertTrue(r.maxOf { abs(it) } < 1e-6, "re-integrated residual from $from: ${r.maxOf { abs(it) }}")
        }
        // Without the correction the bias shows: the update is doing the work.
        val raw = window(biased, 1000, 20)
        assertTrue(M3.maxAbsDiff(raw.deltaV, window(biased, 1000, 20, bg, ba).deltaV) > 1e-3)
    }

    @Test
    fun `bias Jacobians match finite differences of re-integration`() {
        val bg0 = M3.scale(ImuNoise.PHONE.gyroBias0, 0.5)
        val ba0 = M3.scale(ImuNoise.PHONE.accelBias0, 0.5)
        val base = window(biased, 1300, 100, bg0, ba0)
        val dRdBg = NumericJacobian.central(
            { bg -> SO3.log(M3.mul(M3.transpose(base.deltaR), window(biased, 1300, 100, bg, ba0).deltaR)) },
            bg0,
        )
        val dVdBg = NumericJacobian.central({ bg -> window(biased, 1300, 100, bg, ba0).deltaV }, bg0)
        val dPdBg = NumericJacobian.central({ bg -> window(biased, 1300, 100, bg, ba0).deltaP }, bg0)
        val dVdBa = NumericJacobian.central({ ba -> window(biased, 1300, 100, bg0, ba).deltaV }, ba0)
        val dPdBa = NumericJacobian.central({ ba -> window(biased, 1300, 100, bg0, ba).deltaP }, ba0)
        assertJacobian(rows(base.dRdBg), dRdBg)
        assertJacobian(rows(base.dVdBg), dVdBg)
        assertJacobian(rows(base.dPdBg), dPdBg)
        assertJacobian(rows(base.dVdBa), dVdBa)
        assertJacobian(rows(base.dPdBa), dPdBa)
    }

    @Test
    fun `residual Jacobians match NumericJacobian at three random states`() {
        val rnd = Random(3640)
        fun vec(scale: Double) = DoubleArray(3) { scale * rnd.nextGaussian() }
        val bg0 = M3.scale(ImuNoise.PHONE.gyroBias0, 0.5)
        val ba0 = M3.scale(ImuNoise.PHONE.accelBias0, 0.5)
        val pre = window(biased, 400, 100, bg0, ba0)
        repeat(3) { n ->
            // State i: random pose and velocity, biases off the linearisation point so the bias terms are live.
            val si = NavState(SO3.exp(vec(1.5)), vec(1.0), vec(0.5), M3.add(bg0, vec(0.01)), M3.add(ba0, vec(0.1)))
            val c = pre.corrected(si.gyroBias, si.accelBias)
            val t = pre.dt
            // State j: the prediction from i, perturbed, so every residual block is non-zero.
            val pj = DoubleArray(3) { si.p[it] + si.v[it] * t + gravity[it] * t * t / 2 + M3.mv(si.r, c.deltaP)[it] }
            val vj = DoubleArray(3) { si.v[it] + gravity[it] * t + M3.mv(si.r, c.deltaV)[it] }
            val sj = NavState(
                M3.mul(M3.mul(si.r, c.deltaR), SO3.exp(vec(0.2))),
                M3.add(pj, vec(0.1)),
                M3.add(vj, vec(0.1)),
                vec(0.01),
                vec(0.1),
            )
            val analytic = pre.residualJacobians(si, sj, gravity)
            val plus = { s: NavState, d: DoubleArray -> s.retract(d) }
            val numI = NumericJacobian.manifold({ s: NavState -> pre.residual(s, sj, gravity) }, si, NavState.DIM, plus)
            val numJ = NumericJacobian.manifold({ s: NavState -> pre.residual(si, s, gravity) }, sj, NavState.DIM, plus)
            try {
                assertJacobian(analytic.stateI, numI)
                assertJacobian(analytic.stateJ, numJ)
            } catch (e: AssertionError) {
                throw AssertionError("random state $n: ${e.message}", e)
            }
            assertTrue(pre.residual(si, sj, gravity).all { abs(it) > 1e-6 }, "state $n: every residual entry live")
        }
    }

    @Test
    fun `covariance matches a Monte Carlo of the white noise it models`() {
        val noise = ImuNoise.PHONE
        val dt = 1.0 / 200
        val steps = 20
        val runs = 2000
        val from = 900
        val samples = (from until from + steps).map { k ->
            val a = sim200.imu[k]
            val b = sim200.imu[k + 1]
            Pair(mean(a.gyro, b.gyro), mean(a.accel, b.accel))
        }
        val nominal = window(sim200, from, steps, noise = noise)
        val sg = noise.gyroDensity / sqrt(dt)
        val sa = noise.accelDensity / sqrt(dt)
        val rnd = Random(77)
        val errors = Array(runs) {
            val p = ImuPreintegration(zero, zero, ImuNoise.NONE)
            for ((g, a) in samples) {
                val gn = DoubleArray(3) { g[it] + sg * rnd.nextGaussian() }
                p.integrate(dt, gn, DoubleArray(3) { a[it] + sa * rnd.nextGaussian() })
            }
            SO3.log(M3.mul(M3.transpose(nominal.deltaR), p.deltaR)) +
                M3.sub(p.deltaV, nominal.deltaV) + M3.sub(p.deltaP, nominal.deltaP)
        }
        val cov = nominal.covariance
        for (i in 0 until 9) {
            for (j in 0 until 9) {
                val scale = sqrt(cov[i][i] * cov[j][j])
                assertEquals(cov[i][j], cov[j][i], 1e-12 * scale, "symmetric at $i, $j")
                val sample = errors.sumOf { it[i] * it[j] } / runs
                assertTrue(abs(sample - cov[i][j]) < 0.15 * scale, "[$i][$j]: sample $sample, propagated ${cov[i][j]}")
            }
        }
        assertTrue(ImuPreintegration(zero, zero, ImuNoise.NONE).covariance.all { r -> r.all { it == 0.0 } })
        assertTrue(window(sim200, from, steps).covariance.all { r -> r.all { it == 0.0 } }, "no noise, no covariance")
    }

    @Test
    fun `zero rotation rate integrates exactly through the small-angle branches`() {
        val bg = doubleArrayOf(0.01, -0.02, 0.03)
        val ba = doubleArrayOf(0.1, 0.0, -0.1)
        val f = doubleArrayOf(0.3, -0.2, 9.81)
        val p = ImuPreintegration(bg, ba, ImuNoise.PIXEL_DEFAULT)
        repeat(40) { p.integrate(0.005, bg, M3.add(f, ba)) }
        assertEquals(0.2, p.dt, 1e-15)
        assertTrue(M3.maxAbsDiff(M3.I, p.deltaR) == 0.0)
        assertTrue(M3.maxAbsDiff(M3.scale(f, 0.2), p.deltaV) < 1e-14)
        assertTrue(M3.maxAbsDiff(M3.scale(f, 0.02), p.deltaP) < 1e-14)
        assertTrue(M3.maxAbsDiff(M3.scale(M3.I, -0.2), p.dRdBg) < 1e-15)
    }

    @Test
    fun `200 Hz samples with a gap over MAX_GAP_NS mark the window invalid, the limit itself does not`() {
        fun gapped(skip: Int): ImuPreintegration {
            val p = ImuPreintegration(zero, zero, ImuNoise.PIXEL_DEFAULT)
            val idx = (500..540).toList() + (540 + skip..600).toList()
            for ((a, b) in idx.zipWithNext()) {
                val sa = sim200.imu[a]
                val sb = sim200.imu[b]
                p.integrate(sb.t - sa.t, mean(sa.gyro, sb.gyro), mean(sa.accel, sb.accel))
            }
            assertEquals(0.5, p.dt, 1e-12)
            return p
        }
        assertTrue(window(sim200, 500, 100).valid)
        assertTrue(gapped(10).valid, "a 50 ms step is at the limit")
        assertFalse(gapped(11).valid, "a 55 ms step is over it")
    }

    @Test
    fun `zero, negative and non-finite dt are rejected`() {
        val p = ImuPreintegration(zero, zero, ImuNoise.PIXEL_DEFAULT)
        for (dt in doubleArrayOf(0.0, -0.005, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows<IllegalArgumentException> { p.integrate(dt, zero, zero) }
        }
        assertEquals(0.0, p.dt)
        assertTrue(p.valid)
    }

    @Test
    fun `PIXEL_DEFAULT holds the stated placeholders`() {
        val n = ImuNoise.PIXEL_DEFAULT
        assertEquals(1.7e-4, n.gyroDensity)
        assertEquals(2.0e-3, n.accelDensity)
        assertEquals(1e-5, n.gyroBiasWalk)
        assertEquals(3e-4, n.accelBiasWalk)
    }
}
