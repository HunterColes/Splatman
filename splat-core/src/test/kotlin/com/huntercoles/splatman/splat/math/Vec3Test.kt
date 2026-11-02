package com.huntercoles.splatman.splat.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Vec3Test {

    @Test
    fun `arithmetic is componentwise`() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(-4f, 5f, 0.5f)
        assertVec(Vec3(-3f, 7f, 3.5f), a + b)
        assertVec(Vec3(5f, -3f, 2.5f), a - b)
        assertVec(Vec3(2f, 4f, 6f), a * 2f)
        assertVec(Vec3(2f, 4f, 6f), 2f * a)
        assertVec(Vec3(0.5f, 1f, 1.5f), a / 2f)
        assertVec(Vec3(-1f, -2f, -3f), -a)
    }

    @Test
    fun `dot product`() {
        assertEquals(-4f + 10f + 1.5f, Vec3(1f, 2f, 3f) dot Vec3(-4f, 5f, 0.5f), TOL)
        assertEquals(0f, Vec3.UNIT_X dot Vec3.UNIT_Y, TOL)
    }

    @Test
    fun `cross product is right-handed`() {
        assertVec(Vec3.UNIT_Z, Vec3.UNIT_X cross Vec3.UNIT_Y)
        assertVec(Vec3.UNIT_X, Vec3.UNIT_Y cross Vec3.UNIT_Z)
        assertVec(Vec3.UNIT_Y, Vec3.UNIT_Z cross Vec3.UNIT_X)
        assertVec(-Vec3.UNIT_Z, Vec3.UNIT_Y cross Vec3.UNIT_X)
        assertVec(Vec3(-3f, 6f, -3f), Vec3(1f, 2f, 3f) cross Vec3(4f, 5f, 6f))
    }

    @Test
    fun `length and normalized`() {
        val v = Vec3(3f, 0f, 4f)
        assertEquals(5f, v.length(), TOL)
        assertEquals(25f, v.lengthSquared(), TOL)
        assertVec(Vec3(0.6f, 0f, 0.8f), v.normalized())
        assertEquals(1f, Vec3(1f, -2f, 7f).normalized().length(), TOL)
    }

    @Test
    fun `normalizing the zero vector throws`() {
        assertThrows<IllegalArgumentException> { Vec3.ZERO.normalized() }
    }
}
