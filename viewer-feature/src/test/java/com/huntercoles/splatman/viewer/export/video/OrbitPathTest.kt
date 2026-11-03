package com.huntercoles.splatman.viewer.export.video

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos

class OrbitPathTest {

    private val framing = SceneFraming.of(
        floatArrayOf(1f, 2f, 3f, 3f, 2f, 3f, 2f, 4f, 1f, 2f, 0f, 5f),
        4,
        60f,
    )
    private val camera = GaussianCameraController().apply {
        setAspectRatio(1f)
        setInitialView(framing.target, framing.radius)
        orbitStep(2, -1) // the user turned the scan before exporting
    }
    private val path = OrbitPath.of(camera.getRotation(), camera.getDistance(), framing.target)

    @Test
    fun `last frame's pose is the first frame's, so the clip loops`() {
        val first = path.view(0).values
        val after = path.view(path.frames).values
        for (i in first.indices) assertEquals(first[i], after[i], EPS, "view[$i]")
        assertClose(path.eye(0), path.eye(path.frames))
    }

    @Test
    fun `six seconds at thirty frames`() {
        assertEquals(180, path.frames)
        assertEquals(180, OrbitPath.FPS * OrbitPath.SECONDS)
    }

    @Test
    fun `every step turns the same angle about world up`() {
        val expected = 2.0 * PI / path.frames
        for (k in 0 until path.frames) {
            val a = horizontal(path.eye(k) - path.target)
            val b = horizontal(path.eye(k + 1) - path.target)
            val angle = acos((a.dot(b) / (a.length() * b.length())).coerceIn(-1f, 1f).toDouble())
            assertEquals(expected, angle, 1e-3, "step $k")
            // Same handedness every step: never doubles back.
            assertTrue(a.cross(b).y > 0f, "step $k turns the other way")
        }
    }

    @Test
    fun `centred on the scan at the viewer's distance and elevation`() {
        assertClose(framing.target, path.target)
        val startHeight = path.eye(0).y - path.target.y
        for (k in 0..path.frames) {
            val eye = path.eye(k)
            assertEquals(camera.getDistance(), (eye - path.target).length(), 1e-3f, "distance at $k")
            assertEquals(startHeight, eye.y - path.target.y, 1e-3f, "elevation at $k")
            // The scan's centre is in the middle of every frame: straight ahead of the camera.
            val view = path.view(k)
            val c = path.target
            val x = view[0, 0] * c.x + view[0, 1] * c.y + view[0, 2] * c.z + view[0, 3]
            val y = view[1, 0] * c.x + view[1, 1] * c.y + view[1, 2] * c.z + view[1, 3]
            assertEquals(0f, x, 1e-3f, "x at $k")
            assertEquals(0f, y, 1e-3f, "y at $k")
        }
    }

    @Test
    fun `frame 0 is the viewer's own view`() {
        camera.setTarget(framing.target)
        val viewer = camera.getViewMatrix().values
        val first = path.view(0).values
        for (i in first.indices) assertEquals(viewer[i], first[i], EPS, "view[$i]")
    }

    @Test
    fun `the library's reset pose orbits at the initial elevation`() {
        val reset = GaussianCameraController().apply { setInitialView(framing.target, framing.radius) }
        val p = OrbitPath.of(reset.getRotation(), reset.getDistance(), framing.target)
        assertEquals(
            GaussianCameraController.INITIAL_PITCH_DEG.toDouble(),
            Math.toDegrees(p.elevationRad.toDouble()),
            1e-3,
        )
    }

    @Test
    fun `a straight-down view is kept off the pole`() {
        val down = com.huntercoles.splatman.splat.math.Quat.fromAxisAngle(Vec3.UNIT_X, (-PI / 2).toFloat())
        val p = OrbitPath.of(down, 2f, Vec3.ZERO)
        assertTrue(abs(p.elevationRad) <= OrbitPath.MAX_ELEVATION_RAD + 1e-6f)
        for (v in p.view(0).values) assertTrue(v.isFinite())
    }

    private fun horizontal(v: Vec3) = Vec3(v.x, 0f, v.z)

    private fun assertClose(a: Vec3, b: Vec3) {
        assertEquals(a.x, b.x, EPS)
        assertEquals(a.y, b.y, EPS)
        assertEquals(a.z, b.z, EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
