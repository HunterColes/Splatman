package com.huntercoles.splatman.splat.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI

class QuatTest {

    private val one = Quat(1f, 0f, 0f, 0f)
    private val i = Quat(0f, 1f, 0f, 0f)
    private val j = Quat(0f, 0f, 1f, 0f)
    private val k = Quat(0f, 0f, 0f, 1f)
    private val quarterTurn = (PI / 2).toFloat()

    @Test
    fun `Hamilton table`() {
        assertQuat(k, i * j)
        assertQuat(i, j * k)
        assertQuat(j, k * i)
        assertQuat(-1f * k, j * i)
        assertQuat(Quat(-1f, 0f, 0f, 0f), i * i)
        assertQuat(Quat(-1f, 0f, 0f, 0f), j * j)
        assertQuat(Quat(-1f, 0f, 0f, 0f), k * k)
        assertQuat(Quat(-1f, 0f, 0f, 0f), i * j * k)
        assertQuat(i, one * i)
    }

    @Test
    fun `90 degrees about X maps Y to Z and Z to -Y`() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_X, quarterTurn)
        assertVec(Vec3.UNIT_X, q.rotate(Vec3.UNIT_X))
        assertVec(Vec3.UNIT_Z, q.rotate(Vec3.UNIT_Y))
        assertVec(-Vec3.UNIT_Y, q.rotate(Vec3.UNIT_Z))
        assertRotationMatrixAgrees(q)
    }

    @Test
    fun `90 degrees about Y maps Z to X and X to -Z`() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_Y, quarterTurn)
        assertVec(Vec3.UNIT_Y, q.rotate(Vec3.UNIT_Y))
        assertVec(Vec3.UNIT_X, q.rotate(Vec3.UNIT_Z))
        assertVec(-Vec3.UNIT_Z, q.rotate(Vec3.UNIT_X))
        assertRotationMatrixAgrees(q)
    }

    @Test
    fun `90 degrees about Z maps X to Y and Y to -X`() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn)
        assertVec(Vec3.UNIT_Z, q.rotate(Vec3.UNIT_Z))
        assertVec(Vec3.UNIT_Y, q.rotate(Vec3.UNIT_X))
        assertVec(-Vec3.UNIT_X, q.rotate(Vec3.UNIT_Y))
        assertRotationMatrixAgrees(q)
    }

    @Test
    fun `toMat3 of 90 degrees about Z`() {
        val expected = Mat3.fromRows(
            0f, -1f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )
        assertMat(expected, Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn).toMat3())
        assertMat(Mat3.identity(), Quat.IDENTITY.toMat3())
    }

    @Test
    fun `product composes rotations right to left`() {
        val qx = Quat.fromAxisAngle(Vec3.UNIT_X, quarterTurn)
        val qz = Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn)
        // X first (Y -> Z), then Z (Z stays Z).
        assertVec(Vec3.UNIT_Z, (qz * qx).rotate(Vec3.UNIT_Y))
        // Z first (Y -> -X), then X (-X stays -X).
        assertVec(-Vec3.UNIT_X, (qx * qz).rotate(Vec3.UNIT_Y))
        assertMat(qz.toMat3() * qx.toMat3(), (qz * qx).toMat3())
    }

    @Test
    fun `conjugate undoes the rotation`() {
        val q = Quat.fromAxisAngle(Vec3(1f, 2f, -0.5f), 1.1f)
        assertQuat(Quat.IDENTITY, q * q.conjugate())
        val v = Vec3(0.3f, -0.7f, 0.2f)
        assertVec(v, q.conjugate().rotate(q.rotate(v)))
        assertQuat(Quat(0.1f, -0.2f, -0.3f, -0.4f), Quat(0.1f, 0.2f, 0.3f, 0.4f).conjugate())
    }

    @Test
    fun `normalized has unit norm and keeps direction`() {
        val n = Quat(2f, 0f, 0f, 2f).normalized()
        assertEquals(1f, n.norm(), TOL)
        assertQuat(Quat(0.70710677f, 0f, 0f, 0.70710677f), n)
    }

    @Test
    fun `normalizing a zero quaternion throws`() {
        assertThrows<IllegalArgumentException> { Quat(0f, 0f, 0f, 0f).normalized() }
    }

    private fun assertRotationMatrixAgrees(q: Quat) {
        val m = q.toMat3()
        for (v in listOf(Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z, Vec3(0.3f, -0.5f, 0.8f))) {
            assertVec(q.rotate(v), m * v)
        }
    }

    private operator fun Float.times(q: Quat) = Quat(this * q.w, this * q.x, this * q.y, this * q.z)
}
