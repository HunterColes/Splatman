package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Frames are made the way the phone gives them: the [Quat] maps the device frame to the start frame (as
 * GyroIntegrator gives it), and gravity is the accelerometer's vector in the device frame, pointing up at rest
 * (Android). The start frame holds the phone upright: camera (device -Z) horizontal, up along device +Y.
 */
class GyroCoverageTest {
    private val up = Vec3(0f, 1f, 0f)
    private val right = Vec3(1f, 0f, 0f)

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** Turned [yawDeg] round up, then tilted [pitchDeg] (negative = camera down). */
    private fun orientation(yawDeg: Float, pitchDeg: Float = 0f): Quat =
        Quat.fromAxisAngle(up, rad(yawDeg)) * Quat.fromAxisAngle(right, rad(pitchDeg))

    /** What the accelerometer reads at [q]: world up seen in the device frame, 9.81 long. */
    private fun gravityAt(q: Quat): Vec3 = q.conjugate().rotate(up) * 9.81f

    private fun GyroCoverage.frame(yawDeg: Float, pitchDeg: Float = 0f): Boolean {
        val q = orientation(yawDeg, pitchDeg)
        return add(q, gravityAt(q))
    }

    private val all = (1 shl Coverage.SEGMENTS) - 1

    @Test
    fun `A1 a full 360-degree yaw turn at level pitch fills every LEVEL segment and no other band`() {
        val c = GyroCoverage()
        for (i in 0 until 72) assertTrue(c.frame(i * 5f), "frame at ${i * 5} degrees")
        assertEquals(all, c.cells.level)
        assertEquals(0, c.cells.low)
        assertEquals(0, c.cells.high)
        assertEquals(1f / 3f, c.fraction, 1e-6f)
    }

    @Test
    fun `A2 tilting down 30 degrees lands in LOW`() {
        val c = GyroCoverage()
        c.frame(0f)
        assertTrue(c.frame(0f, -30f))
        assertEquals(1, c.cells.count(CoverageBand.LOW))
        assertTrue(c.cells.covered(CoverageBand.LOW, 0))
        assertEquals(1, c.cells.count(CoverageBand.LEVEL))
        assertEquals(0, c.cells.high)
    }

    @Test
    fun `A2b tilting up 45 degrees lands in HIGH`() {
        val c = GyroCoverage()
        c.frame(0f)
        assertTrue(c.frame(0f, 45f))
        assertTrue(c.cells.covered(CoverageBand.HIGH, 0))
        assertEquals(0, c.cells.low)
    }

    @Test
    fun `A3 without gravity every frame counts as LEVEL`() {
        val c = GyroCoverage()
        c.add(orientation(0f, -30f), null)
        c.add(orientation(0f, 45f), null)
        assertEquals(0, c.cells.low)
        assertEquals(0, c.cells.high)
        assertEquals(1, c.cells.count(CoverageBand.LEVEL))
    }

    @Test
    fun `H1 gravity of zero length or NaN is treated as absent`() {
        val c = GyroCoverage()
        c.add(orientation(0f, -30f), Vec3(0f, 0f, 0f))
        c.add(orientation(0f, 45f), Vec3(Float.NaN, 9.81f, 0f))
        c.add(orientation(0f, -45f), Vec3(0f, Float.NaN, Float.NaN))
        assertEquals(0, c.cells.low)
        assertEquals(0, c.cells.high)
        assertEquals(1, c.cells.count(CoverageBand.LEVEL))
        assertTrue(c.cells.covered(CoverageBand.LEVEL, 0))
    }

    @Test
    fun `H2 yaw wraps at 360 without a gap`() {
        val c = GyroCoverage()
        c.frame(0f) // the reference: segment 0
        // Either way round, these straddle the reference and fill segments 22, 23, 0 and 1 with nothing missing.
        for (yaw in listOf(-20f, -10f, -1f, 1f, 10f, 20f, 361f, 379f, -361f)) assertTrue(c.frame(yaw), "yaw $yaw")
        assertEquals(4, c.cells.count(CoverageBand.LEVEL))
        for (s in listOf(22, 23, 0, 1)) assertTrue(c.cells.covered(CoverageBand.LEVEL, s), "segment $s")
    }

    @Test
    fun `H3 reset forgets the reference orientation and the cells`() {
        val c = GyroCoverage()
        c.frame(0f)
        c.frame(90f)
        c.reset()
        assertEquals(CoverageCells(), c.cells)
        assertEquals(0f, c.fraction)
        c.frame(90f) // the new reference: segment 0, not the old 90 degrees
        assertEquals(CoverageCells(level = 1), c.cells)
    }

    @Test
    fun `H4 the reference is the first frame's yaw, not the start frame's`() {
        val c = GyroCoverage()
        c.frame(100f)
        c.frame(107f)
        assertEquals(CoverageCells(level = 1), c.cells)
    }
}
