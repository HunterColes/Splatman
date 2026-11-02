package com.huntercoles.splatman.viewer.rendering.gl

import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

class SceneFramingTest {

    @Test
    fun `targets the mean and fits the radius in the field of view`() {
        // 100 points on a unit sphere around (10, 20, 30): radius 1.
        val n = 100
        val positions = FloatArray(3 * n)
        for (i in 0 until n) {
            val axis = i % 3
            val sign = if (i % 2 == 0) 1f else -1f
            positions[3 * i] = 10f
            positions[3 * i + 1] = 20f
            positions[3 * i + 2] = 30f
            positions[3 * i + axis] += sign
        }
        val f = SceneFraming.of(positions, n, 60f)
        assertEquals(10f, f.target.x, 0.05f)
        assertEquals(20f, f.target.y, 0.05f)
        assertEquals(30f, f.target.z, 0.05f)
        assertEquals(1f, f.radius, 0.05f)
        assertEquals((f.radius / sin(Math.toRadians(30.0))).toFloat(), f.distance, 1e-4f)
    }

    @Test
    fun `a few far floaters do not shrink the scene`() {
        val n = 100
        val positions = FloatArray(3 * n) { if (it % 3 == 0) ((it / 3) % 10 - 4.5f) / 4.5f else 0f }
        positions[0] = 1e4f
        val f = SceneFraming.of(positions, n, 60f)
        assertTrue(f.radius < 200f, "radius ${f.radius}")
    }

    @Test
    fun `an empty or non-finite cloud gets a default view`() {
        val empty = SceneFraming.of(FloatArray(0), 0, 60f)
        assertEquals(Vec3.ZERO, empty.target)
        assertEquals(1f, empty.radius)
        val nan = SceneFraming.of(floatArrayOf(Float.NaN, 0f, 0f), 1, 60f)
        assertEquals(Vec3.ZERO, nan.target)
        assertTrue(nan.distance.isFinite())
    }

    @Test
    fun `a single point gets the minimum radius`() {
        val f = SceneFraming.of(floatArrayOf(1f, 2f, 3f), 1, 60f)
        assertEquals(Vec3(1f, 2f, 3f), f.target)
        assertEquals(SceneFraming.MIN_RADIUS, f.radius)
    }
}
