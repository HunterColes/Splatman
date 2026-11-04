package com.huntercoles.splatman.splat.sfm.vi

import com.huntercoles.splatman.splat.math.Quat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.sqrt

class CameraImuRotationTest {
    private val phone = ImuNoise.PHONE
    private val px = ImuNoise.PIXEL_DEFAULT
    private val pixelBiased =
        ImuNoise(px.gyroDensity, px.accelDensity, px.gyroBiasWalk, px.accelBiasWalk, phone.gyroBias0, phone.accelBias0)
    private val five = Math.toRadians(5.0)

    private fun prior(truth: DoubleArray, axis: DoubleArray, angle: Double = five): Quat =
        CameraImuRotation.quatOf(Mat3.mul(SO3.exp(Mat3.scale(axis, angle / Mat3.norm(axis))), truth))

    /** ViSim pairs (k, k + 10), 1 s apart, gyro preintegrated at zero bias, SfM rotations with 0.03 deg noise. */
    private class SimPairs(scenario: ViScenario, noise: ImuNoise, seed: Long = 5) {
        val fx = ViAlignmentFixture(
            ViSim(seed, scenario, ViSimConfig(noise = noise, pointCount = 1)),
            rotationSigma = Math.toRadians(0.03),
            positionSigma = 5e-4,
        )
        private val pairs = (0 until fx.sim.keyframes.size - 10).map { it to it + 10 }
        val gyro = pairs.map { fx.window(it.first, it.second) }
        val sfm = pairs.map { fx.sfmRelative(it.first, it.second) }
    }

    private fun errDeg(fit: CameraImuRotationFit, truth: DoubleArray) =
        ViAlignmentFixture.rotationAngleDeg(fit.rCS, truth)

    @Test
    fun `ORBIT and ROOM recover R_CS within 0_5 deg from priors 5 deg off, with the gyro bias`() {
        for (scenario in listOf(ViScenario.ORBIT, ViScenario.ROOM)) {
            val p = SimPairs(scenario, pixelBiased)
            for (axis in listOf(doubleArrayOf(1.0, 2.0, -1.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 1.0, 0.0))) {
                val fit = CameraImuRotation.refine(prior(p.fx.rCS, axis), p.gyro, p.sfm)
                assertNull(fit.keptPrior, "$scenario: kept the prior: ${fit.keptPrior}")
                assertTrue(errDeg(fit, p.fx.rCS) < 0.5, "$scenario axis ${axis.toList()}: off by ${errDeg(fit, p.fx.rCS)} deg")
                assertEquals(5.0, Math.toDegrees(fit.angleFromPrior), 0.5)
                val be = Mat3.norm(Mat3.sub(fit.gyroBias, p.fx.trueGyroBias)) / Mat3.norm(p.fx.trueGyroBias)
                assertTrue(be < 0.1, "$scenario: gyro bias off by ${be * 100} %")
                val cov = assertNotNullCov(fit)
                for (i in 0..2) assertTrue(cov[4 * i] > 0 && sqrt(cov[4 * i]) < Math.toRadians(0.5), "sd $i")
                assertEquals(
                    0.0,
                    ViAlignmentFixture.rotationAngleDeg(CameraImuRotation.matrixOf(fit.rotation), fit.rCS),
                    1e-3,
                )
                println("R_CS $scenario axis ${axis.toList()}: error ${"%.3f".format(errDeg(fit, p.fx.rCS))} deg")
            }
        }
    }

    private fun assertNotNullCov(fit: CameraImuRotationFit): DoubleArray {
        assertNotNull(fit.covariance)
        return fit.covariance!!
    }

    @Test
    fun `fewer than 10 pairs over 5 deg keep the prior`() {
        val p = SimPairs(ViScenario.ROOM, pixelBiased)
        // 0.1 s pairs turn by under 5 deg.
        val short = (0 until 100).map { it to it + 1 }
        val gyro = short.map { p.fx.window(it.first, it.second) }
        val sfm = short.map { p.fx.sfmRelative(it.first, it.second) }
        val q = prior(p.fx.rCS, doubleArrayOf(1.0, 0.0, 0.0))
        val fit = CameraImuRotation.refine(q, gyro, sfm)
        assertTrue(fit.usablePairs < CameraImuRotation.MIN_PAIRS)
        assertNotNull(fit.keptPrior)
        assertNull(fit.covariance)
        assertEquals(q, fit.rotation)
        assertEquals(0.0, fit.angleFromPrior)

        val big = p.sfm.indices.filter { Mat3.norm(SO3.log(p.sfm[it])) > Math.toRadians(6.0) }.take(9)
        val nine = CameraImuRotation.refine(q, big.map { p.gyro[it] }, big.map { p.sfm[it] })
        assertEquals(9, nine.usablePairs)
        assertNotNull(nine.keptPrior)
    }

    @Test
    fun `a fit that moves the prior over 15 deg keeps the prior`() {
        val p = SimPairs(ViScenario.ROOM, pixelBiased)
        val q = prior(p.fx.rCS, doubleArrayOf(1.0, -1.0, 2.0), Math.toRadians(25.0))
        val fit = CameraImuRotation.refine(q, p.gyro, p.sfm)
        assertNotNull(fit.keptPrior)
        assertTrue(fit.keptPrior!!.contains("over 15"), fit.keptPrior)
        assertEquals(q, fit.rotation)

        // 12 deg off is inside the bound: the fit is taken and lands on the truth.
        val near = CameraImuRotation.refine(prior(p.fx.rCS, doubleArrayOf(1.0, -1.0, 2.0), Math.toRadians(12.0)), p.gyro, p.sfm)
        assertNull(near.keptPrior)
        assertTrue(errDeg(near, p.fx.rCS) < 0.5)
    }

    /** Pairs turning 6 to 20 deg in 1 s about axes in S's x-y plane only: none about the optical axis (S z). */
    private fun planarPairs(truth: DoubleArray, outliers: Int = 0): Pair<List<ImuPreintegration>, List<DoubleArray>> {
        val rnd = Random(21)
        val bias = doubleArrayOf(0.01, -0.008, 0.005)
        val gyro = ArrayList<ImuPreintegration>()
        val sfm = ArrayList<DoubleArray>()
        repeat(60) { k ->
            val phi = rnd.nextDouble() * 2 * Math.PI
            val angle = Math.toRadians(6.0 + 14.0 * rnd.nextDouble())
            val w = doubleArrayOf(angle * kotlin.math.cos(phi), angle * kotlin.math.sin(phi), 0.0)
            val pre = ImuPreintegration(DoubleArray(3), DoubleArray(3), ImuNoise.NONE)
            repeat(200) {
                val g = DoubleArray(3) { w[it] + bias[it] + px.gyroDensity * sqrt(200.0) * rnd.nextGaussian() }
                pre.integrate(0.005, g, doubleArrayOf(0.0, 9.81, 0.0))
            }
            gyro += pre
            val noise = DoubleArray(3) { Math.toRadians(0.03) * rnd.nextGaussian() }
            val bad = if (k < outliers) SO3.exp(doubleArrayOf(0.0, 0.0, Math.toRadians(10.0))) else Mat3.I
            sfm += Mat3.mul(Mat3.mul(Mat3.mul(Mat3.mul(truth, SO3.exp(w)), Mat3.transpose(truth)), SO3.exp(noise)), bad)
        }
        return Pair(gyro, sfm)
    }

    @Test
    fun `an orbit with little rotation about the optical axis still pins all three axes`() {
        val truth = Mat3.transpose(ViSim.R_SC_PORTRAIT)
        val (gyro, sfm) = planarPairs(truth)
        val fit = CameraImuRotation.refine(prior(truth, doubleArrayOf(0.3, -0.4, 1.0)), gyro, sfm)
        assertNull(fit.keptPrior)
        assertTrue(errDeg(fit, truth) < 0.5, "off by ${errDeg(fit, truth)} deg")
        val cov = assertNotNullCov(fit)
        assertTrue(cov.all { it.isFinite() })
        assertTrue(Mat3.maxAbs(Mat3.sub(fit.gyroBias, doubleArrayOf(0.01, -0.008, 0.005))) < 1e-3)
    }

    @Test
    fun `Huber weighting shrugs off SfM pairs 10 deg wrong`() {
        val truth = Mat3.transpose(ViSim.R_SC_PORTRAIT)
        val (gyro, sfm) = planarPairs(truth, outliers = 4)
        val fit = CameraImuRotation.refine(prior(truth, doubleArrayOf(1.0, 1.0, 0.0)), gyro, sfm)
        assertNull(fit.keptPrior)
        assertTrue(errDeg(fit, truth) < 0.5, "off by ${errDeg(fit, truth)} deg")
    }

    @Test
    fun `quaternion and matrix round trip, including the Pixel's w = 0 prior`() {
        val pixel = Quat(0f, -0.7071068f, 0.7071068f, 0f)
        val m = CameraImuRotation.matrixOf(pixel)
        val expected = doubleArrayOf(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0)
        assertTrue(Mat3.maxAbs(Mat3.sub(m, expected)) < 1e-6, m.toList().toString())
        val back = CameraImuRotation.matrixOf(CameraImuRotation.quatOf(m))
        assertTrue(Mat3.maxAbs(Mat3.sub(back, m)) < 1e-6)
        val rnd = Random(3)
        repeat(50) {
            val r = SO3.exp(DoubleArray(3) { 1.5 * rnd.nextGaussian() })
            val q = CameraImuRotation.quatOf(r)
            assertTrue(q.w >= 0f)
            assertTrue(Mat3.maxAbs(Mat3.sub(CameraImuRotation.matrixOf(q), r)) < 1e-6)
        }
    }
}
