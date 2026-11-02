package com.huntercoles.splatman.viewer.rendering

import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/** The viewer's gesture maths: orbit, pan, scene-relative zoom clamps, fling, auto-rotate and reset. */
class CameraGestureMathTest {

    private val centre = Vec3(1f, 2f, 3f)
    private val radius = 2f

    private fun framed(aspect: Float = 9f / 16f) = GaussianCameraController().apply {
        setAspectRatio(aspect)
        setInitialView(centre, radius)
    }

    private fun GaussianCameraController.eye(): Vec3 =
        getTarget() + getRotation().rotate(Vec3.UNIT_Z) * getDistance()

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, eps, "x")
        assertEquals(expected.y, actual.y, eps, "y")
        assertEquals(expected.z, actual.z, eps, "z")
    }

    @Test
    fun `the initial view frames the scene to fill 70 percent of the short edge`() {
        val portrait = framed(9f / 16f)
        val halfShort = atan(tan(Math.toRadians(30.0)) * (9.0 / 16.0))
        assertEquals(0.7, radius / (portrait.getDistance() * tan(halfShort)), 1e-4)

        val landscape = framed(16f / 9f)
        assertEquals(0.7, radius / (landscape.getDistance() * tan(Math.toRadians(30.0))), 1e-4)
        assertVec(centre, portrait.getTarget())
    }

    @Test
    fun `the initial view is a front three-quarter view from above`() {
        val offset = framed().eye() - centre
        assertTrue(offset.x > 0f, "turned to one side")
        assertTrue(offset.y > 0f, "looking down")
        assertTrue(offset.z > offset.x, "still mostly from the front")
    }

    @Test
    fun `one-finger orbit turns the eye around the centre at the same distance`() {
        val c = framed()
        val before = c.eye()
        c.onRotate(3f, 0f)
        assertVec(centre, c.getTarget())
        assertEquals(c.getDistance(), (c.eye() - centre).length(), 1e-3f)
        assertTrue((c.eye() - before).length() > 0.1f)
        // A horizontal drag is a yaw about world up: the eye's height is unchanged.
        assertEquals(before.y, c.eye().y, 1e-3f)
    }

    @Test
    fun `two-finger pan moves the target along the camera's right axis, scaled by distance`() {
        val c = framed()
        val right = c.getRotation().rotate(Vec3.UNIT_X)
        val step = ViewerConstants.PAN_SENSITIVITY * c.getDistance()
        c.onPan(10f, 0f)
        assertVec(centre + right * (10f * step), c.getTarget())
    }

    @Test
    fun `pinch zoom is clamped to the scene so it cannot vanish or be passed through`() {
        val c = framed()
        val start = c.getDistance()
        repeat(200) { c.onZoom(0.5f) }
        assertEquals(start * GaussianCameraController.MIN_FACTOR, c.getDistance(), 1e-4f)
        assertTrue(c.getDistance() > radius * 0.1f)
        repeat(200) { c.onZoom(2f) }
        assertEquals(start * GaussianCameraController.MAX_FACTOR, c.getDistance(), 1e-3f)
    }

    @Test
    fun `a zero, negative or NaN zoom factor is ignored`() {
        val c = framed()
        val d = c.getDistance()
        c.onZoom(0f)
        c.onZoom(-1f)
        c.onZoom(Float.NaN)
        assertEquals(d, c.getDistance())
    }

    @Test
    fun `reset returns to the initial view after orbit, pan and zoom`() {
        val c = framed()
        val rotation = c.getRotation()
        val distance = c.getDistance()
        c.onRotate(5f, -3f)
        c.onPan(4f, 2f)
        c.onZoom(3f)
        c.resetToInitial(animated = false)
        assertEquals(rotation, c.getRotation())
        assertEquals(distance, c.getDistance())
        assertVec(centre, c.getTarget())
    }

    @Test
    fun `the animated reset eases to the initial view over the reset duration`() {
        val c = framed()
        val distance = c.getDistance()
        c.onZoom(4f)
        val far = c.getDistance()
        c.resetToInitial(animated = true)
        assertTrue(c.isMoving)
        c.advance(ViewerConstants.RESET_SECONDS / 2)
        assertTrue(c.getDistance() < far && c.getDistance() > distance)
        c.advance(ViewerConstants.RESET_SECONDS)
        assertFalse(c.isMoving)
        assertEquals(distance, c.getDistance(), 1e-4f)
        assertVec(centre, c.getTarget())
    }

    @Test
    fun `a touch stops the reset animation where it is`() {
        val c = framed()
        c.onZoom(4f)
        c.resetToInitial(animated = true)
        c.advance(ViewerConstants.RESET_SECONDS / 4)
        c.stopMotion()
        val held = c.getDistance()
        c.advance(1f)
        assertEquals(held, c.getDistance())
    }

    @Test
    fun `a fling keeps orbiting, slows down with friction and stops`() {
        val c = framed()
        c.fling(5f, 0f)
        val a = c.eye()
        c.advance(0.1f)
        val b = c.eye()
        c.advance(0.1f)
        val d = c.eye()
        val first = (b - a).length()
        val second = (d - b).length()
        assertTrue(first > 0f)
        assertTrue(second < first, "friction slows it")
        repeat(200) { c.advance(0.1f) }
        assertFalse(c.isMoving)
    }

    @Test
    fun `auto-rotate yaws about world up and keeps the distance and height`() {
        val c = framed()
        val before = c.eye()
        c.autoRotate = true
        c.advance(1f)
        val after = c.eye()
        assertTrue((after - before).length() > 0.1f)
        assertEquals(before.y, after.y, 1e-3f)
        assertEquals(c.getDistance(), (after - centre).length(), 1e-3f)

        c.autoRotate = false
        c.advance(1f)
        assertVec(after, c.eye())
    }

    @Test
    fun `orbit and zoom steps for TalkBack and keys move by one step each`() {
        val c = framed()
        val d = c.getDistance()
        c.zoomStep(1)
        assertEquals(d / ViewerConstants.ZOOM_STEP, c.getDistance(), 1e-4f)
        c.zoomStep(-1)
        assertEquals(d, c.getDistance(), 1e-4f)

        val before = c.eye() - centre
        c.orbitStep(1, 0)
        val after = c.eye() - centre
        val cos = (before dot after) / (before.length() * after.length())
        assertTrue(cos < 1f - 1e-4f)
        assertTrue(abs(before.y - after.y) < 1e-3f)
    }
}
