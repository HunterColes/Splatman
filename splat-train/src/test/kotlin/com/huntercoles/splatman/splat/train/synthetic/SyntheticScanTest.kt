package com.huntercoles.splatman.splat.train.synthetic

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.train.frames.FrameFixtures
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** The synthetic scan of #224: renders, true poses and the IMU they imply (ADR 0003 conventions). */
class SyntheticScanTest {

    @Test
    fun `a 64x48 render of the cactus is not blank and is deterministic for a seed`() {
        val a = SyntheticScan.render(cactus, 4, small, ScanPath.WobblyArc(), seed = 7)
        val b = SyntheticScan.render(cactus, 4, small, ScanPath.WobblyArc(), seed = 7)
        val pixels = 64 * 48
        for (f in a.frames) {
            val lit = (0 until pixels).count { p -> (0 until 3).any { (f.image.rgba[4 * p + it].toInt() and 0xFF) > 16 } }
            assertTrue(lit > pixels / 20, "frame ${f.index}: only $lit lit pixels")
            assertTrue(lit < pixels, "frame ${f.index}: no background left, the subject does not fit")
        }
        for (i in a.frames.indices) {
            assertArrayEquals(a.frames[i].image.rgba, b.frames[i].image.rgba, "frame $i image")
            assertEquals(a.frames[i].pose, b.frames[i].pose)
            assertEquals(a.frames[i].timestampNs, b.frames[i].timestampNs)
        }
        assertArrayEquals(a.gyro.xyz, b.gyro.xyz)
        assertArrayEquals(a.accel.xyz, b.accel.xyz)

        val other = SyntheticScan.render(cactus, 4, small, ScanPath.WobblyArc(), seed = 8)
        assertFalse(other.gyro.xyz.contentEquals(a.gyro.xyz), "the seed must drive the wobble")
    }

    @Test
    fun `IMU runs at 200 Hz over the frames' span`() {
        val scan = SyntheticScan.render(cactus, 5, tiny, ScanPath.Orbit(durationS = 2.0), seed = 1)
        assertEquals(401, scan.gyro.count)
        assertEquals(scan.frames.first().timestampNs, scan.gyro.timestampsNs.first())
        assertEquals(scan.frames.last().timestampNs, scan.gyro.timestampsNs.last())
        for (k in 1 until scan.gyro.count) {
            assertEquals(5_000_000L, scan.gyro.timestampsNs[k] - scan.gyro.timestampsNs[k - 1])
        }
        assertArrayEquals(scan.gyro.timestampsNs, scan.accel.timestampsNs)
    }

    @Test
    fun `integrated gyro reproduces the true relative rotations of an orbit`() {
        assertGyroMatchesTruth(SyntheticScan.render(cactus, 13, tiny, ScanPath.Orbit(), seed = 3))
    }

    @Test
    fun `integrated gyro reproduces the true relative rotations of a wobbly arc`() {
        for (seed in 1L..3L) {
            assertGyroMatchesTruth(SyntheticScan.render(cactus, 17, tiny, ScanPath.WobblyArc(wobbleDeg = 8.0), seed))
        }
    }

    @Test
    fun `gyro convention holds for another camera-to-IMU rotation`() {
        val r = Quat.fromAxisAngle(Vec3(0.3f, -1f, 0.5f), 0.7f)
        val scan = SyntheticScan.render(cactus, 9, tiny, ScanPath.WobblyArc(), seed = 5, rotationCcvImu = r)
        assertGyroMatchesTruth(scan, r)
    }

    @Test
    fun `a level orbit's accelerometer is gravity plus the centripetal pull`() {
        val scan = SyntheticScan.render(cactus, 9, tiny, ScanPath.Orbit(durationS = 6.0), seed = 2)
        val omega = 2 * Math.PI / 6.0
        val offset = scan.frames[0].pose.translationM - scan.subjectCentre
        val ring = sqrt((offset.x * offset.x + offset.z * offset.z).toDouble())
        for (i in scan.frames.indices) {
            val k = ((scan.frames[i].timestampNs - scan.accel.timestampsNs[0]) / 5_000_000L).toInt()
            val f = Vec3(scan.accel.xyz[3 * k], scan.accel.xyz[3 * k + 1], scan.accel.xyz[3 * k + 2])
            val world = scan.imuRotation(i).rotate(f)
            assertEquals(SyntheticScan.GRAVITY_MS2, world.y.toDouble(), 1e-3, "frame $i: vertical")
            val toCentre = (scan.subjectCentre - scan.frames[i].pose.translationM).let { Vec3(it.x, 0f, it.z) }
            val pull = (Vec3(world.x, 0f, world.z) dot toCentre.normalized()).toDouble()
            assertEquals(omega * omega * ring, pull, 1e-3 * ring + 1e-3, "frame $i: centripetal")
        }
    }

    @Test
    fun `bias shifts every sample by exactly the bias and noise is seeded`() {
        val path = ScanPath.Orbit(durationS = 1.0)
        val clean = SyntheticScan.render(cactus, 3, tiny, path, seed = 4)
        val gb = Vec3(0.01f, -0.02f, 0.03f)
        val ab = Vec3(0.2f, 0.1f, -0.3f)
        val biased = SyntheticScan.render(cactus, 3, tiny, path, seed = 4, ImuNoise(gyroBiasRadS = gb, accelBiasMS2 = ab))
        val g = floatArrayOf(gb.x, gb.y, gb.z)
        val a = floatArrayOf(ab.x, ab.y, ab.z)
        for (i in clean.gyro.xyz.indices) {
            assertEquals(g[i % 3], biased.gyro.xyz[i] - clean.gyro.xyz[i], 1e-5f)
            assertEquals(a[i % 3], biased.accel.xyz[i] - clean.accel.xyz[i], 1e-5f)
        }
        val noise = ImuNoise(gyroStdRadS = 0.01f, accelStdMS2 = 0.1f)
        val n1 = SyntheticScan.render(cactus, 3, tiny, path, seed = 4, noise)
        val n2 = SyntheticScan.render(cactus, 3, tiny, path, seed = 4, noise)
        assertArrayEquals(n1.gyro.xyz, n2.gyro.xyz)
        assertArrayEquals(n1.accel.xyz, n2.accel.xyz)
        val diff = n1.accel.xyz.indices.map { (n1.accel.xyz[it] - clean.accel.xyz[it]).toDouble() }
        assertEquals(0.1, sqrt(diff.sumOf { it * it } / diff.size), 0.02)
    }

    private fun assertGyroMatchesTruth(scan: SyntheticScan, rotationCcvImu: Quat = scan.rotationCcvImu) {
        val pairs = scan.frames.indices.zipWithNext() + listOf(0 to scan.frames.size - 1)
        for ((i, j) in pairs) {
            // R_W_Imu = R_W_Cgl * R_Cgl_Ccv * R_Ccv_Imu, built here from the true pose, not by SyntheticScan.
            val ri = scan.frames[i].pose.rotation * CGL_FROM_CCV * rotationCcvImu
            val rj = scan.frames[j].pose.rotation * CGL_FROM_CCV * rotationCcvImu
            val truth = ri.conjugate() * rj
            val gyro = SyntheticScan.integrateGyro(scan.gyro, scan.frames[i].timestampNs, scan.frames[j].timestampNs)
            val err = angle(truth.conjugate() * gyro)
            assertTrue(err < 1e-3, "frames $i..$j: gyro vs truth $err rad")
            if (i + 1 == j) assertTrue(angle(truth) > 1e-2, "frames $i..$j: the camera must actually turn")
        }
    }

    private fun angle(q: Quat): Double {
        val v = sqrt((q.x * q.x + q.y * q.y + q.z * q.z).toDouble())
        return 2 * atan2(v, abs(q.w.toDouble()))
    }

    companion object {
        private val CGL_FROM_CCV = Quat(0f, 1f, 0f, 0f)
        private val cactus by lazy { FrameFixtures.load(FrameFixtures.CACTUS_UP) }
        private val small = CameraIntrinsics(50f, 50f, 31.5f, 23.5f, 64, 48)
        private val tiny = CameraIntrinsics(12f, 12f, 7.5f, 5.5f, 16, 12)
    }
}
