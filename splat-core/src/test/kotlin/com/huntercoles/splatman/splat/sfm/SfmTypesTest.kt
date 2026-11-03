package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SeedPoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SfmTypesTest {
    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val id = Quat(1f, 0f, 0f, 0f)
    private fun frame(i: Int) = SfmFrame(i, i * 1000L, "f$i.jpg", k)
    private val imu = ImuSamples(longArrayOf(1, 2), FloatArray(6))

    @Test
    fun `frame rejects negative index and empty path`() {
        assertThrows<IllegalArgumentException> { SfmFrame(-1, 0, "a", k) }
        assertThrows<IllegalArgumentException> { SfmFrame(0, 0, "", k) }
    }

    @Test
    fun `imu rejects xyz size mismatch`() {
        assertEquals(2, imu.count)
        assertThrows<IllegalArgumentException> { ImuSamples(longArrayOf(1, 2), FloatArray(5)) }
    }

    @Test
    fun `options reject out of range values`() {
        assertThrows<IllegalArgumentException> { SfmOptions(maxFeatures = 0) }
        assertThrows<IllegalArgumentException> { SfmOptions(longSidePx = 0) }
        assertThrows<IllegalArgumentException> { SfmOptions(matchWindow = 0) }
        assertThrows<IllegalArgumentException> { SfmOptions(baIterations = -1) }
    }

    @Test
    fun `input accepts ordered frames and rejects empty or unordered`() {
        SfmInput(listOf(frame(0), frame(2)), imu, imu, id)
        assertThrows<IllegalArgumentException> { SfmInput(emptyList(), imu, imu, id) }
        assertThrows<IllegalArgumentException> { SfmInput(listOf(frame(1), frame(0)), imu, imu, id) }
        assertThrows<IllegalArgumentException> { SfmInput(listOf(frame(1), frame(1)), imu, imu, id) }
    }

    @Test
    fun `pair requires i before j`() {
        FramePair(0, 1, null)
        assertThrows<IllegalArgumentException> { FramePair(1, 1, id) }
        assertThrows<IllegalArgumentException> { FramePair(-1, 1, null) }
    }

    private fun model(
        registered: BooleanArray = booleanArrayOf(true, false),
        rot: Int = 8,
        trans: Int = 6,
        pts: Int = 6,
        col: Int = 6,
        obsFrame: IntArray = intArrayOf(0, 0),
        obsPoint: IntArray = intArrayOf(0, 1),
        uv: Int = 4,
    ) = SparseModel(
        registered, FloatArray(rot), FloatArray(trans), FloatArray(pts), ByteArray(col),
        obsFrame, obsPoint, FloatArray(uv),
    )

    @Test
    fun `sparse model accepts consistent arrays`() {
        val m = model()
        assertEquals(2, m.frameCount)
        assertEquals(2, m.pointCount)
        assertEquals(2, m.observationCount)
    }

    @Test
    fun `sparse model rejects every inconsistency`() {
        assertThrows<IllegalArgumentException> { model(rot = 7) }
        assertThrows<IllegalArgumentException> { model(trans = 5) }
        assertThrows<IllegalArgumentException> { model(pts = 5, col = 5) }
        assertThrows<IllegalArgumentException> { model(col = 3) }
        assertThrows<IllegalArgumentException> { model(obsPoint = intArrayOf(0)) }
        assertThrows<IllegalArgumentException> { model(uv = 3) }
        assertThrows<IllegalArgumentException> { model(obsFrame = intArrayOf(0, 2)) }
        assertThrows<IllegalArgumentException> { model(obsFrame = intArrayOf(0, 1)) }
        assertThrows<IllegalArgumentException> { model(obsPoint = intArrayOf(0, 2)) }
        assertThrows<IllegalArgumentException> { model(obsPoint = intArrayOf(-1, 0)) }
    }

    private fun stats(total: Int = 2, reg: Int = 1, pts: Int = 1) =
        SfmStats(total, reg, pts, 0.5f, 0.4f, emptyMap())

    @Test
    fun `stats reject impossible counts`() {
        assertThrows<IllegalArgumentException> { stats(reg = 3) }
        assertThrows<IllegalArgumentException> { stats(reg = -1) }
        assertThrows<IllegalArgumentException> { stats(pts = -1) }
    }

    @Test
    fun `result checks poses and seed against stats`() {
        val pose = CameraPose(id, Vec3(0f, 0f, 0f))
        val seed = SeedPoints(1, FloatArray(3), ByteArray(3))
        val empty = SeedPoints(0, FloatArray(0), ByteArray(0))
        SfmResult(listOf(pose, null), seed, stats())
        assertThrows<IllegalArgumentException> { SfmResult(listOf(pose), seed, stats()) }
        assertThrows<IllegalArgumentException> { SfmResult(listOf(pose, pose), seed, stats()) }
        assertThrows<IllegalArgumentException> { SfmResult(listOf(pose, null), empty, stats()) }
    }
}
