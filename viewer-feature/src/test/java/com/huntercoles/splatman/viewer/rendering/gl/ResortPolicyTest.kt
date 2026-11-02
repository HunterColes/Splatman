package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class ResortPolicyTest {

    /** Eye on a circle of radius 5 around the origin at [degrees] from +Z, looking at the origin. */
    private fun orbit(degrees: Double, radius: Float = 5f): FloatArray {
        val a = Math.toRadians(degrees)
        val eye = Vec3((radius * sin(a)).toFloat(), 0f, (radius * cos(a)).toFloat())
        return Mat4.lookAt(eye, Vec3.ZERO, Vec3.UNIT_Y).values
    }

    @Test
    fun `the first view always sorts`() {
        assertTrue(ResortPolicy().needsSort(orbit(0.0)))
    }

    @Test
    fun `the same view does not sort again`() {
        val p = ResortPolicy()
        p.markSorted(orbit(0.0))
        assertFalse(p.needsSort(orbit(0.0)))
    }

    @Test
    fun `a turn over half a degree sorts, under it does not`() {
        val p = ResortPolicy(sceneRadius = 1000f) // the eye's move stays under the move threshold
        p.markSorted(orbit(0.0))
        assertFalse(p.needsSort(orbit(0.4)))
        assertTrue(p.needsSort(orbit(0.6)))
    }

    @Test
    fun `moving the eye without turning sorts`() {
        val p = ResortPolicy(sceneRadius = 1f)
        val view = Mat4.lookAt(Vec3(0f, 0f, 5f), Vec3.ZERO, Vec3.UNIT_Y).values
        p.markSorted(view)
        val tiny = Mat4.lookAt(Vec3(0.0005f, 0f, 5f), Vec3(0.0005f, 0f, 0f), Vec3.UNIT_Y).values
        val moved = Mat4.lookAt(Vec3(0.01f, 0f, 5f), Vec3(0.01f, 0f, 0f), Vec3.UNIT_Y).values
        assertFalse(p.needsSort(tiny))
        assertTrue(p.needsSort(moved))
    }

    @Test
    fun `eye, forward and depth come from the view matrix`() {
        val view = Mat4.lookAt(Vec3(1f, 2f, 7f), Vec3(1f, 2f, 0f), Vec3.UNIT_Y).values
        assertArrayEquals(floatArrayOf(1f, 2f, 7f), ResortPolicy.eyeOf(view), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), ResortPolicy.forwardOf(view), 1e-6f)
        val depths = FloatArray(2)
        ResortPolicy.depths(view, floatArrayOf(1f, 2f, 0f, 5f, 5f, -3f), 2, depths)
        assertArrayEquals(floatArrayOf(7f, 10f), depths, 1e-5f)
    }
}
