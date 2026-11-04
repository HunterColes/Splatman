package com.huntercoles.splatman.splat.sfm.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

class ViAlignmentTest {
    private val phone = ImuNoise.PHONE
    private val px = ImuNoise.PIXEL_DEFAULT

    /** The Pixel placeholder densities and walks with PHONE's turn-on biases (gyro 0.014 rad/s, accel 0.15 m/s^2). */
    private val pixelBiased =
        ImuNoise(px.gyroDensity, px.accelDensity, px.gyroBiasWalk, px.accelBiasWalk, phone.gyroBias0, phone.accelBias0)

    private val sfmRotationSigma = Math.toRadians(0.03)
    private val sfmPositionSigma = 5e-4

    private fun sim(seed: Long, scenario: ViScenario, noise: ImuNoise = pixelBiased) =
        ViSim(seed, scenario, ViSimConfig(noise = noise, pointCount = 1))

    private fun noisyFixture(seed: Long, scenario: ViScenario, outlier: Pair<Int, DoubleArray>? = null) =
        ViAlignmentFixture(
            sim(seed, scenario),
            rotationSigma = sfmRotationSigma,
            positionSigma = sfmPositionSigma,
            seed = seed + 100,
            outlier = outlier,
        )

    private fun ok(r: ViAlignResult): ViInit {
        assertTrue(r is ViInit, "expected a ViInit, got $r")
        return r as ViInit
    }

    private fun scaleErrorPct(fx: ViAlignmentFixture, r: ViInit) = (r.scale / fx.metresPerUnit - 1) * 100
    private fun gravityErrorDeg(fx: ViAlignmentFixture, r: ViInit) = ViAlignmentFixture.angleDeg(r.gravityG, fx.gravityW)
    private fun gyroBiasErrorPct(fx: ViAlignmentFixture, b: DoubleArray) =
        Mat3.norm(Mat3.sub(b, fx.trueGyroBias)) / Mat3.norm(fx.trueGyroBias) * 100

    @Test
    fun `noise-free ORBIT with constant biases is recovered exactly`() {
        val noise = ImuNoise(0.0, 0.0, 0.0, 0.0, phone.gyroBias0, phone.accelBias0)
        val fx = ViAlignmentFixture(sim(7, ViScenario.ORBIT, noise))
        val r = ok(fx.align())
        assertEquals(fx.metresPerUnit, r.scale, fx.metresPerUnit * 1e-4)
        assertTrue(gravityErrorDeg(fx, r) < 1e-3, "gravity off by ${gravityErrorDeg(fx, r)} deg")
        assertEquals(ViAlignment.GRAVITY, Mat3.norm(r.gravityG), 1e-9)
        assertTrue(Mat3.maxAbs(Mat3.sub(r.gyroBias, phone.gyroBias0)) < 1e-5, "gyro bias ${r.gyroBias.toList()}")
        assertTrue(Mat3.maxAbs(Mat3.sub(r.accelBias, phone.accelBias0)) < 2e-3, "accel bias ${r.accelBias.toList()}")
        for (k in listOf(0, 100, 300)) {
            val err = Mat3.norm(Mat3.sub(r.velocities[k], fx.trueVelocityW(k)))
            assertTrue(err < 2e-3, "velocity $k off by $err m/s")
        }
    }

    @Test
    fun `ORBIT and ROOM with IMU and SfM noise meet scale 1 %, gravity 0_5 deg, gyro bias 10 %`() {
        val rows = ArrayList<String>()
        for (scenario in listOf(ViScenario.ORBIT, ViScenario.ROOM)) {
            for (seed in 1L..4L) {
                val fx = noisyFixture(seed, scenario)
                val r = ok(fx.align())
                val se = scaleErrorPct(fx, r)
                val ge = gravityErrorDeg(fx, r)
                val be = gyroBiasErrorPct(fx, r.gyroBias)
                rows += "| $scenario | $seed | %.3f | %.4f | %.2f | %.0f |".format(se, ge, be, r.conditioning)
                assertTrue(abs(se) < 1.0, "$scenario seed $seed: scale off by $se %")
                assertTrue(ge < 0.5, "$scenario seed $seed: gravity off by $ge deg")
                assertTrue(be < 10.0, "$scenario seed $seed: gyro bias off by $be %")
                assertTrue(r.conditioning >= ViAlignment.MIN_CONDITIONING)
            }
        }
        println("| scenario | seed | scale error % | gravity error deg | gyro bias error % | conditioning |")
        println("|---|---|---|---|---|---|")
        rows.forEach(::println)
    }

    @Test
    fun `an outlier SfM pose 20 cm off does not pull the scale`() {
        val fx = noisyFixture(1, ViScenario.ORBIT, outlier = 150 to doubleArrayOf(0.2, 0.0, 0.0))
        val r = ok(fx.align())
        assertTrue(abs(scaleErrorPct(fx, r)) < 1.0, "scale off by ${scaleErrorPct(fx, r)} %")
        assertTrue(gravityErrorDeg(fx, r) < 0.5, "gravity off by ${gravityErrorDeg(fx, r)} deg")
    }

    @Test
    fun `STATIC's still part alone fails with too little motion, the whole sweep aligns`() {
        val s = sim(3, ViScenario.STATIC)
        val still = ViAlignmentFixture(s, to = 19, rotationSigma = sfmRotationSigma, positionSigma = sfmPositionSigma)
            .align()
        assertTrue(still is ViAlignFailure && still.reason == ViFailureReason.TOO_LITTLE_MOTION, "got $still")
        assertTrue((still as ViAlignFailure).conditioning < ViAlignment.MIN_CONDITIONING)
        assertTrue(still.message.startsWith("too little motion"))

        val sweep = ViAlignmentFixture(s, rotationSigma = sfmRotationSigma, positionSigma = sfmPositionSigma)
        val r = ok(sweep.align())
        assertTrue(abs(scaleErrorPct(sweep, r)) < 2.0, "sweep scale off by ${scaleErrorPct(sweep, r)} %")
    }

    @Test
    fun `turning about the camera centre without translation fails cleanly with too little motion`() {
        val rSC = ViSim.R_SC_PORTRAIT
        val lever = doubleArrayOf(0.01, 0.03, -0.005)
        val centre = doubleArrayOf(0.2, -0.1, 1.3)
        val rate = 200.0
        fun rGS(t: Double) = SO3.exp(doubleArrayOf(0.4 * sin(0.7 * t), 0.3 * sin(0.5 * t + 1), 0.25 * t))
        fun pGS(t: Double) = Mat3.sub(centre, Mat3.mv(rGS(t), lever))
        val imu = SyntheticImu(::rGS, ::pGS)
        val keyTimes = (0..100).map { it / 10.0 }
        val poses = keyTimes.map { ViPose(Mat3.mul(rGS(it), rSC), centre.copyOf()) }
        val pres = keyTimes.zipWithNext { a, b -> imu.preintegrate(a, b, rate) }
        val r = ViAlignment.align(poses, pres, Mat3.transpose(rSC), lever)
        assertTrue(r is ViAlignFailure && r.reason == ViFailureReason.TOO_LITTLE_MOTION, "got $r")

        // The same with a little SfM position noise: still no scale, still a clean failure.
        val rnd = Random(4)
        val noisy = poses.map { p -> ViPose(p.rWC, DoubleArray(3) { p.pWC[it] + 1e-4 * rnd.nextGaussian() }) }
        val r2 = ViAlignment.align(noisy, pres, Mat3.transpose(rSC), lever)
        assertTrue(r2 is ViAlignFailure && r2.reason == ViFailureReason.TOO_LITTLE_MOTION, "got $r2")
    }

    @Test
    fun `SfM positions mirrored through the origin give a non-positive scale`() {
        val fx = ViAlignmentFixture(sim(2, ViScenario.ROOM))
        val mirrored = fx.poses.map { ViPose(it.rWC, Mat3.scale(it.pWC, -1.0)) }
        val r = ViAlignment.align(mirrored, fx.preintegrations, fx.rCS, fx.leverArm)
        assertTrue(r is ViAlignFailure && r.reason == ViFailureReason.NON_POSITIVE_SCALE, "got $r")
    }

    @Test
    fun `too few keyframes, an IMU gap and mismatched inputs are refused`() {
        val fx = ViAlignmentFixture(sim(2, ViScenario.ROOM), to = 10)
        val two = ViAlignment.align(fx.poses.take(2), fx.preintegrations.take(1), fx.rCS, fx.leverArm)
        assertTrue(two is ViAlignFailure && two.reason == ViFailureReason.TOO_FEW_KEYFRAMES, "got $two")

        val gapped = ImuPreintegration(DoubleArray(3), DoubleArray(3), ImuNoise.NONE).apply {
            integrate(0.2, DoubleArray(3), doubleArrayOf(0.0, 9.81, 0.0))
        }
        val pres = fx.preintegrations.toMutableList().also { it[3] = gapped }
        val gap = ViAlignment.align(fx.poses, pres, fx.rCS, fx.leverArm)
        assertTrue(gap is ViAlignFailure && gap.reason == ViFailureReason.IMU_GAP, "got $gap")

        assertThrows<IllegalArgumentException> {
            ViAlignment.align(fx.poses, fx.preintegrations.drop(1), fx.rCS, fx.leverArm)
        }
    }

    @Test
    fun `gyro bias alone is recovered from SfM rotations`() {
        val noise = ImuNoise(0.0, 0.0, 0.0, 0.0, phone.gyroBias0, DoubleArray(3))
        val fx = ViAlignmentFixture(sim(9, ViScenario.ROOM, noise))
        val rWS = fx.poses.map { Mat3.mul(it.rWC, fx.rCS) }
        val bg = ViAlignment.gyroBias(rWS, fx.preintegrations)
        assertTrue(Mat3.maxAbs(Mat3.sub(bg, phone.gyroBias0)) < 1e-6, "gyro bias ${bg.toList()}")
    }

    @Test
    fun `the arrow solver matches a dense solve`() {
        val rnd = Random(12)
        val nv = 12
        val nb = 3
        val sys = ArrowSystem(nv, nb)
        val rows = ArrayList<DoubleArray>()
        repeat(60) {
            val i = rnd.nextInt(nv - 3)
            val vi = if (rnd.nextBoolean()) intArrayOf(i) else intArrayOf(i, i + 3)
            val vc = DoubleArray(vi.size) { rnd.nextGaussian() }
            val bc = DoubleArray(nb) { rnd.nextGaussian() }
            val rhs = rnd.nextGaussian()
            val w = 0.5 + rnd.nextDouble()
            sys.addRow(vi, vc, bc, rhs, w)
            val full = DoubleArray(nv + nb + 2)
            for (p in vi.indices) full[vi[p]] = vc[p]
            for (c in 0 until nb) full[nv + c] = bc[c]
            full[nv + nb] = rhs
            full[nv + nb + 1] = w
            rows += full
        }
        val n = nv + nb
        val h = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (r in rows) {
            for (p in 0 until n) {
                for (q in 0 until n) h[p][q] += r[n + 1] * r[p] * r[q]
                b[p] += r[n + 1] * r[p] * r[n]
            }
        }
        val dense = ViDense.solveSpd(h, b)!!
        val arrow = sys.solve()!!
        for (i in 0 until nv) assertEquals(dense[i], arrow.v[i], 1e-9)
        for (c in 0 until nb) assertEquals(dense[nv + c], arrow.x[c], 1e-9)
    }

    @Test
    fun `Jacobi eigenvalues of a known symmetric matrix`() {
        // Q diag(4, 1, -2) Q^T for a rotation Q.
        val q = SO3.exp(doubleArrayOf(0.3, -0.5, 0.8))
        val d = doubleArrayOf(4.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -2.0)
        val m = Mat3.mul(Mat3.mul(q, d), Mat3.transpose(q))
        val (values, vectors) = ViDense.symmetricEigen(Array(3) { m.copyOfRange(3 * it, 3 * it + 3) })
        val sorted = values.sorted()
        assertEquals(-2.0, sorted[0], 1e-12)
        assertEquals(1.0, sorted[1], 1e-12)
        assertEquals(4.0, sorted[2], 1e-12)
        for (k in 0..2) {
            val v = DoubleArray(3) { vectors[it][k] }
            val mv = Mat3.mv(m, v)
            for (i in 0..2) assertEquals(values[k] * v[i], mv[i], 1e-12)
        }
        assertTrue(ViDense.solveSpd(Array(3) { m.copyOfRange(3 * it, 3 * it + 3) }, DoubleArray(3)) == null)
    }
}

/**
 * IMU samples of an analytic trajectory `R_GS(t)`, `p_GS(t)` by central differences (h = 1e-4 s, errors near 1e-8),
 * noise-free, gravity `(0, 0, -9.81)`.
 */
class SyntheticImu(private val rGS: (Double) -> DoubleArray, private val pGS: (Double) -> DoubleArray) {
    private val h = 1e-4
    private val g = doubleArrayOf(0.0, 0.0, -ViSim.GRAVITY)

    fun gyro(t: Double): DoubleArray {
        val d = Mat3.tmul(rGS(t - h), rGS(t + h))
        return Mat3.scale(SO3.log(d), 1 / (2 * h))
    }

    fun accel(t: Double): DoubleArray {
        val a = DoubleArray(3) { (pGS(t + h)[it] - 2 * pGS(t)[it] + pGS(t - h)[it]) / (h * h) }
        return Mat3.mtv(rGS(t), Mat3.sub(a, g))
    }

    /** Midpoint samples from [t0] to [t1] at [rate] Hz into a zero-bias, noise-free preintegration. */
    fun preintegrate(t0: Double, t1: Double, rate: Double): ImuPreintegration {
        val pre = ImuPreintegration(DoubleArray(3), DoubleArray(3), ImuNoise.NONE)
        val steps = Math.round((t1 - t0) * rate).toInt()
        val dt = (t1 - t0) / steps
        for (k in 0 until steps) {
            val t = t0 + (k + 0.5) * dt
            pre.integrate(dt, gyro(t), accel(t))
        }
        return pre
    }
}
