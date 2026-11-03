package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.math.assertVec
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.DepthImage
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SeedPoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SeedAccumulatorTest {

    private val rgbK = CameraIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 40f, width = 101, height = 81)
    private val identity = CameraPose(Quat(1f, 0f, 0f, 0f), Vec3(0f, 0f, 0f))

    /** RGB image whose pixel (u, v) is (u, v, 7). */
    private val rgb = RgbImage(rgbK.width, rgbK.height, ByteArray(rgbK.width * rgbK.height * 4).also {
        for (v in 0 until rgbK.height) for (u in 0 until rgbK.width) {
            val o = (v * rgbK.width + u) * 4
            it[o] = u.toByte()
            it[o + 1] = v.toByte()
            it[o + 2] = 7
            it[o + 3] = -1
        }
    })

    private fun colour(s: SeedPoints, i: Int) =
        Triple(s.colours[3 * i].toInt() and 0xFF, s.colours[3 * i + 1].toInt() and 0xFF, s.colours[3 * i + 2].toInt())

    private fun point(s: SeedPoints, i: Int) = Vec3(s.positions[3 * i], s.positions[3 * i + 1], s.positions[3 * i + 2])

    @Test
    fun `defaults are ADR 0004`() {
        val a = SeedAccumulator()
        assertEquals(0.01f, a.voxelM)
        assertEquals(200_000, a.maxPoints)
        assertEquals(0.3f, a.minConfidence)
    }

    @Test
    fun `one point per 1 cm voxel, first one wins`() {
        val a = SeedAccumulator()
        // in front of the identity camera (looking down -Z in W); the first two share voxel (0, 0, -100)
        val pts = floatArrayOf(
            0.001f, 0.001f, -0.995f,
            0.009f, 0.008f, -0.991f,
            0.011f, 0.001f, -0.995f, // next voxel along x
        )
        assertEquals(2, a.addPoints(pts, floatArrayOf(1f, 1f, 1f), 3, rgbK, identity, rgb))
        val s = a.toSeedPoints()
        assertEquals(2, s.count)
        assertVec(Vec3(0.001f, 0.001f, -0.995f), point(s, 0), 0f)
        // the same voxel again does not add
        assertEquals(0, a.addPoints(floatArrayOf(0.005f, 0.005f, -0.999f), floatArrayOf(1f), 1, rgbK, identity, rgb))
        assertEquals(2, a.count)
    }

    @Test
    fun `feature points need confidence of at least 0_3`() {
        val a = SeedAccumulator()
        val pts = floatArrayOf(0f, 0f, -1f, 0.1f, 0f, -1f, 0.2f, 0f, -1f)
        assertEquals(2, a.addPoints(pts, floatArrayOf(0.29f, 0.3f, 0.9f), 3, rgbK, identity, rgb))
        assertEquals(0.1f, a.toSeedPoints().positions[0])
    }

    @Test
    fun `feature point colour is sampled where it projects in the keyframe`() {
        val a = SeedAccumulator()
        // W (0.1, 0.1, -1) -> Ccv (0.1, -0.1, 1) -> pixel (60, 30)
        a.addPoints(floatArrayOf(0.1f, 0.1f, -1f), floatArrayOf(1f), 1, rgbK, identity, rgb)
        assertEquals(Triple(60, 30, 7), colour(a.toSeedPoints(), 0))
        // behind the camera or outside the image: dropped
        assertEquals(0, a.addPoints(floatArrayOf(0f, 0f, 1f, 5f, 0f, -1f), floatArrayOf(1f, 1f), 2, rgbK, identity, rgb))
    }

    @Test
    fun `the cap stops accumulation`() {
        val a = SeedAccumulator(maxPoints = 5)
        val n = 20
        val pts = FloatArray(3 * n) { if (it % 3 == 0) (it / 3) * 0.02f - 0.2f else if (it % 3 == 2) -2f else 0f }
        assertEquals(5, a.addPoints(pts, FloatArray(n) { 1f }, n, rgbK, identity, rgb))
        assertTrue(a.isFull)
        assertEquals(5, a.toSeedPoints().count)
        assertEquals(0, a.addPoints(floatArrayOf(0.5f, 0.5f, -2f), floatArrayOf(1f), 1, rgbK, identity, rgb))
    }

    @Test
    fun `default cap is 200k`() {
        val a = SeedAccumulator()
        val depthK = CameraIntrinsics(fx = 50f, fy = 50f, cx = 250f, cy = 250f, width = 500, height = 500)
        // 250k pixels on a plane 1 m away, 2 cm apart: one voxel each
        val depth = DepthImage(depthK, FloatArray(500 * 500) { 1f }, null)
        val wideK = CameraIntrinsics(fx = 10f, fy = 10f, cx = 100f, cy = 100f, width = 201, height = 201)
        val wideRgb = RgbImage(201, 201, ByteArray(201 * 201 * 4))
        assertEquals(200_000, a.addDepth(depth, wideK, identity, wideRgb))
        assertEquals(200_000, a.count)
    }

    @Test
    fun `a depth pixel at the principal point lands on the camera axis at its depth`() {
        val depthK = CameraIntrinsics(fx = 20f, fy = 20f, cx = 2f, cy = 3f, width = 5, height = 7)
        val depthM = FloatArray(5 * 7).also { it[3 * 5 + 2] = 1.5f }
        val pose = CameraPose(
            Quat.fromAxisAngle(Vec3(0.3f, 1f, -0.2f).normalized(), 0.7f),
            Vec3(0.4f, 1.2f, -0.3f),
        )
        val a = SeedAccumulator()
        assertEquals(1, a.addDepth(DepthImage(depthK, depthM, null), rgbK, pose, rgb))
        val s = a.toSeedPoints()
        val axis = pose.rotation.rotate(Vec3(0f, 0f, -1f)) // the camera looks down -Z of Cgl
        assertVec(pose.translationM + axis * 1.5f, point(s, 0), 1e-5f)
        // the camera axis meets the colour image at its principal point
        assertEquals(Triple(50, 40, 7), colour(s, 0))
    }

    @Test
    fun `unknown depth is skipped`() {
        val depthK = CameraIntrinsics(fx = 20f, fy = 20f, cx = 1f, cy = 0f, width = 3, height = 1)
        val depth = DepthImage(depthK, floatArrayOf(0f, Float.NaN, 2f), null)
        assertEquals(1, SeedAccumulator().addDepth(depth, rgbK, identity, rgb))
    }

    @Test
    fun `clear reuses the storage`() {
        val a = SeedAccumulator(maxPoints = 2)
        val p = floatArrayOf(0f, 0f, -1f)
        assertEquals(1, a.addPoints(p, floatArrayOf(1f), 1, rgbK, identity, rgb))
        a.clear()
        assertEquals(0, a.count)
        assertEquals(1, a.addPoints(p, floatArrayOf(1f), 1, rgbK, identity, rgb))
    }

    @Test
    fun `rgb must match its intrinsics`() {
        assertThrows<IllegalArgumentException> {
            SeedAccumulator().addPoints(
                floatArrayOf(0f, 0f, -1f), floatArrayOf(1f), 1, rgbK, identity, RgbImage(2, 2, ByteArray(16)),
            )
        }
    }
}
