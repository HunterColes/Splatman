package com.huntercoles.splatman.splat.train.synthetic

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.train.frames.FrameFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The pose error metric of #224: similarity alignment, ATE in path radii, rotation error in degrees. */
class PoseErrorsTest {

    @Test
    fun `the truth against itself scores zero`() {
        for (truth in listOf(circle, scan.truth)) {
            val r = PoseErrors.compare(truth, truth)
            assertEquals(truth.size, r.registered)
            assertEquals(1.0, r.scale, 1e-5)
            assertEquals(0.0, r.ateRmse, 1e-5)
            assertEquals(0.0, r.maxRotationDeg, 1e-2)
        }
    }

    @Test
    fun `the truth under a random similarity scores zero`() {
        val rnd = Random(11)
        repeat(5) {
            val s = 0.2f + 4f * rnd.nextFloat()
            val axis = Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            val q = Quat.fromAxisAngle(axis, 6f * rnd.nextFloat())
            val t = Vec3(5f * rnd.nextFloat(), -3f * rnd.nextFloat(), 2f)
            for (truth in listOf(circle, scan.truth)) {
                val moved = truth.map { CameraPose(q * it.rotation, q.rotate(it.translationM) * s + t) }
                val r = PoseErrors.compare(moved, truth)
                assertEquals(1.0 / s, r.scale, 1e-4 / s)
                assertEquals(0.0, r.ateRmse, 1e-4)
                assertEquals(0.0, r.maxRotationDeg, 2e-2)
            }
        }
    }

    @Test
    fun `a known rotation perturbation shows as its angle`() {
        val turned = circle.mapIndexed { i, p ->
            val axis = if (i % 2 == 0) Vec3.UNIT_X else Vec3(0f, 1f, 1f)
            CameraPose(p.rotation * Quat.fromAxisAngle(axis, Math.toRadians(2.0).toFloat()), p.translationM)
        }
        val r = PoseErrors.compare(turned, circle)
        assertEquals(0.0, r.ateRmse, 1e-5)
        assertEquals(2.0, r.meanRotationDeg, 1e-2)
        assertEquals(2.0, r.maxRotationDeg, 1e-2)
    }

    @Test
    fun `a known centre perturbation gives the least-squares ATE`() {
        // +-d along Y, alternating round an 8-camera level circle: no rotation or shift absorbs it, the fitted scale
        // shrinks to R^2 / (R^2 + d^2) and the RMSE is R d / sqrt(R^2 + d^2), so d / sqrt(R^2 + d^2) path radii.
        val d = 0.3
        val bumped = circle.mapIndexed { i, p ->
            CameraPose(p.rotation, p.translationM + Vec3(0f, if (i % 2 == 0) d.toFloat() else -d.toFloat(), 0f))
        }
        val r = PoseErrors.compare(bumped, circle)
        assertEquals(RADIUS, r.pathRadius, 1e-5)
        assertEquals(RADIUS * RADIUS / (RADIUS * RADIUS + d * d), r.scale, 1e-5)
        assertEquals(d / sqrt(RADIUS * RADIUS + d * d), r.ateRmse, 1e-5)
        assertEquals(0.0, r.maxRotationDeg, 1e-2)
    }

    @Test
    fun `the path radius is taken about the given centre`() {
        val r = PoseErrors.compare(scan.truth, scan.truth, scan.subjectCentre)
        assertEquals(1.0, r.pathRadius / scan.orbitRadius, 0.2)
    }

    @Test
    fun `unregistered frames are left out and counted`() {
        val partial = circle.mapIndexed { i, p -> if (i == 2 || i == 5) null else p }
        val r = PoseErrors.compare(partial, circle)
        assertEquals(8, r.total)
        assertEquals(6, r.registered)
        assertEquals(0.0, r.ateRmse, 1e-5)
        val few = PoseErrors.compare(circle.mapIndexed { i, p -> if (i < 2) p else null }, circle)
        assertEquals(2, few.registered)
        assertTrue(few.ateRmse.isNaN())
    }

    companion object {
        private const val RADIUS = 2.0

        /** 8 cameras evenly round a level circle of [RADIUS] about (1, 0.5, -2), each yawed to face the middle. */
        private val circle = (0 until 8).map { k ->
            val a = 2 * Math.PI * k / 8
            CameraPose(
                Quat.fromAxisAngle(Vec3.UNIT_Y, a.toFloat()),
                Vec3((1 + RADIUS * sin(a)).toFloat(), 0.5f, (-2 + RADIUS * cos(a)).toFloat()),
            )
        }

        private val scan by lazy {
            SyntheticScan.render(
                FrameFixtures.load(FrameFixtures.CACTUS_UP), 12, CameraIntrinsics(12f, 12f, 7.5f, 5.5f, 16, 12),
                ScanPath.WobblyArc(), seed = 9,
            )
        }
    }
}
