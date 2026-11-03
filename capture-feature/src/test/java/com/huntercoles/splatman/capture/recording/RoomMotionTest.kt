package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.MotionMeter
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomMotionTest {
    private val motion = RoomMotion()
    private var ns = 0L
    private var yawDeg = 0f
    private var position = Vec3(0f, 1.5f, 0f)

    private fun pose(yaw: Float = yawDeg, pitch: Float = 0f): CameraPose {
        val q = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.toRadians(yaw.toDouble()).toFloat()) *
            Quat.fromAxisAngle(Vec3(1f, 0f, 0f), Math.toRadians(pitch.toDouble()).toFloat())
        return CameraPose(q, position)
    }

    private fun frame(tracking: Boolean = true, dtNs: Long = FRAME_NS, pitch: Float = 0f) {
        ns += dtNs
        motion.add(pose(pitch = pitch), ns, tracking)
    }

    private fun walkX(metres: Float, stepM: Float = 0.01f) {
        val steps = Math.round(metres / stepM)
        repeat(steps) {
            position += Vec3(stepM, 0f, 0f)
            frame()
        }
    }

    private fun turn(degrees: Float, stepDeg: Float = 2f) {
        val steps = Math.round(degrees / stepDeg)
        repeat(steps) {
            yawDeg += stepDeg
            frame()
        }
    }

    @Test
    fun `a 3 m straight walk at 1 cm steps adds 3 m`() {
        frame()
        walkX(3f)
        assertEquals(3f, motion.pathM, 0.06f)
        val path = motion.path
        assertEquals(0f, path.first().first, 1e-4f)
        assertEquals(3f, path.last().first, 0.06f)
    }

    @Test
    fun `a 2 m jump in one frame adds nothing and starts a new point`() {
        frame()
        position += Vec3(2f, 0f, 0f)
        frame()
        assertEquals(0f, motion.pathM)
        assertEquals(2, motion.path.size)
        walkX(1f)
        assertEquals(1f, motion.pathM, 0.06f)
    }

    @Test
    fun `jitter of 2 cm round one spot adds nothing`() {
        val spot = position
        frame()
        repeat(500) { i ->
            val d = if (i % 2 == 0) 0.02f else -0.02f
            position = spot + Vec3(d, -d, d)
            frame()
        }
        assertEquals(0f, motion.pathM)
        assertEquals(1, motion.path.size)
    }

    @Test
    fun `a gap longer than MAX_GAP_NS adds no length`() {
        frame()
        position += Vec3(0.3f, 0f, 0f)
        frame(dtNs = MotionMeter.MAX_GAP_NS + 1)
        assertEquals(0f, motion.pathM)
        assertEquals(2, motion.path.size)
    }

    @Test
    fun `a non-tracking frame breaks the path`() {
        frame()
        position += Vec3(0.2f, 0f, 0f)
        frame(tracking = false)
        position += Vec3(0.2f, 0f, 0f)
        frame()
        assertEquals(0f, motion.pathM)
        assertEquals(2, motion.path.size)
    }

    @Test
    fun `turning in place 200 degrees is spinning, a 0_4 m walk then is not`() {
        frame()
        turn(200f)
        assertEquals(200f, motion.turnDeg, 0.5f)
        assertTrue(motion.spinning)
        walkX(0.4f)
        assertFalse(motion.spinning)
    }

    @Test
    fun `turning while walking is not spinning`() {
        frame()
        repeat(100) {
            yawDeg += 2f
            position += Vec3(0.01f, 0f, 0f)
            frame()
        }
        assertFalse(motion.spinning)
    }

    @Test
    fun `turn takes the shortest way round across the seam`() {
        yawDeg = 175f
        frame()
        yawDeg = -175f
        frame()
        assertEquals(10f, motion.turnDeg, 0.01f)
    }

    @Test
    fun `frames steeper than 80 degrees add no turn`() {
        frame()
        yawDeg = 90f
        frame(pitch = -85f)
        assertEquals(0f, motion.turnDeg)
        frame()
        assertEquals(90f, motion.turnDeg, 0.01f)
    }

    @Test
    fun `a 360 degree spin makes walk-on due once`() {
        frame()
        turn(200f)
        assertFalse(motion.walkOnDue())
        turn(170f)
        assertTrue(motion.walkOnDue())
        assertFalse(motion.walkOnDue())
        turn(400f)
        assertFalse(motion.walkOnDue())
        motion.reset()
        frame()
        turn(370f)
        assertTrue(motion.walkOnDue())
    }

    @Test
    fun `long spinning keeps the samples bounded and still spinning`() {
        frame()
        turn(36_000f, stepDeg = 0.5f)
        assertTrue(motion.spinning)
        walkX(0.4f)
        assertFalse(motion.spinning)
    }

    @Test
    fun `5000 path points stay within 1024, first and last kept`() {
        frame()
        walkX(5000 * 0.06f, stepM = 0.06f)
        val path = motion.path
        assertTrue(path.size <= RoomMotion.MAX_PATH_POINTS, "size ${path.size}")
        assertTrue(path.size > RoomMotion.MAX_PATH_POINTS / 2, "size ${path.size}")
        assertEquals(0f, path.first().first, 1e-4f)
        assertEquals(position.x, path.last().first, 1e-3f)
        assertEquals(300f, motion.pathM, 0.5f)
    }

    @Test
    fun `reset forgets the path and the turn`() {
        frame()
        walkX(1f)
        turn(90f)
        motion.reset()
        assertEquals(0f, motion.pathM)
        assertEquals(0f, motion.turnDeg)
        assertTrue(motion.path.isEmpty())
        assertFalse(motion.spinning)
    }

    @Test
    fun `roomNearM prefers the depth median, else needs 20 points`() {
        val points = FloatArray(30) { 1f + it * 0.1f }
        assertEquals(0.3f, RoomMotion.roomNearM(0.3f, points, 30))
        assertNull(RoomMotion.roomNearM(null, points, 19))
        // Median of 1.0, 1.1, ..., 2.9: between 1.9 and 2.0.
        assertEquals(1.95f, RoomMotion.roomNearM(null, points, 20)!!, 1e-4f)
        assertEquals(2f, RoomMotion.roomNearM(null, points, 21)!!, 1e-4f)
        assertNull(RoomMotion.roomNearM(null, FloatArray(5), 20))
        assertEquals(0.4f, RoomMotion.ROOM_CLOSE_M)
    }

    private companion object {
        const val FRAME_NS = 33_000_000L
    }
}
