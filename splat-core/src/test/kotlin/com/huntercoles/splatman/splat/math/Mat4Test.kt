package com.huntercoles.splatman.splat.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI

class Mat4Test {

    private val quarterTurn = (PI / 2).toFloat()
    private val q = Quat.fromAxisAngle(Vec3(0.2f, -1f, 0.4f), 0.9f)
    private val t = Vec3(1.5f, -2f, 0.25f)

    @Test
    fun `translation sits at indices 12 to 14`() {
        val m = Mat4.fromRotationTranslation(Quat.IDENTITY, Vec3(4f, 5f, 6f))
        assertEquals(4f, m.values[12])
        assertEquals(5f, m.values[13])
        assertEquals(6f, m.values[14])
        assertEquals(1f, m.values[15])
        assertEquals(0f, m.values[3])
        assertEquals(0f, m.values[7])
        assertEquals(0f, m.values[11])
        assertVec(Vec3(5f, 7f, 9f), m.transformPoint(Vec3(1f, 2f, 3f)))
        assertVec(Vec3(1f, 2f, 3f), m.transformVector(Vec3(1f, 2f, 3f)))
    }

    @Test
    fun `storage is column-major`() {
        val m = mat4Rows(
            1f, 2f, 3f, 4f,
            5f, 6f, 7f, 8f,
            9f, 10f, 11f, 12f,
            13f, 14f, 15f, 16f,
        )
        assertEquals(5f, m.values[1])
        assertEquals(2f, m.values[4])
        assertEquals(7f, m[1, 2])
        assertMat(m, m.transpose().transpose())
        assertEquals(m[1, 2], m.transpose()[2, 1])
    }

    @Test
    fun `identity is neutral`() {
        val m = Mat4.fromRotationTranslation(q, t)
        assertMat(m, Mat4.identity() * m)
        assertMat(m, m * Mat4.identity())
    }

    @Test
    fun `fromRotationTranslation rotates then translates`() {
        val m = Mat4.fromRotationTranslation(Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn), Vec3(10f, 0f, 0f))
        assertVec(Vec3(10f, 1f, 0f), m.transformPoint(Vec3.UNIT_X))
    }

    @Test
    fun `rigidInverse times M is identity`() {
        val m = Mat4.fromRotationTranslation(q, t)
        assertMat(Mat4.identity(), m.rigidInverse() * m)
        assertMat(Mat4.identity(), m * m.rigidInverse())
        assertMat(m.inverse(), m.rigidInverse())
    }

    @Test
    fun `inverse of a general matrix`() {
        val m = mat4Rows(
            4f, 7f, 0f, 0f,
            2f, 6f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 2f,
        )
        val expected = mat4Rows(
            0.6f, -0.7f, 0f, 0f,
            -0.2f, 0.4f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 0.5f,
        )
        assertMat(expected, m.inverse())

        val dense = mat4Rows(
            2f, -1f, 0f, 3f,
            1f, 3f, 2f, -1f,
            0f, 1f, 4f, 2f,
            1f, 0f, -2f, 5f,
        )
        assertMat(Mat4.identity(), dense * dense.inverse(), 1e-5f)
        assertMat(Mat4.identity(), dense.inverse() * dense, 1e-5f)
        val proj = Mat4.perspective(1f, 1.5f, 0.1f, 100f)
        assertMat(Mat4.identity(), proj.inverse() * proj, 1e-5f)
    }

    @Test
    fun `inverse of a singular matrix throws`() {
        val singular = mat4Rows(
            1f, 2f, 3f, 4f,
            2f, 4f, 6f, 8f,
            0f, 1f, 0f, 1f,
            1f, 0f, 1f, 0f,
        )
        assertThrows<IllegalArgumentException> { singular.inverse() }
    }

    @Test
    fun `compose order T_a_c = T_a_b times T_b_c`() {
        val tAB = Mat4.fromRotationTranslation(Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn), Vec3(10f, 0f, 0f))
        val tBC = Mat4.fromRotationTranslation(Quat.IDENTITY, Vec3(0f, 0f, 3f))
        val pC = Vec3(1f, 0f, 0f)
        val tAC = tAB * tBC
        assertVec(tAB.transformPoint(tBC.transformPoint(pC)), tAC.transformPoint(pC))
        assertVec(Vec3(10f, 1f, 3f), tAC.transformPoint(pC))
        // Order matters: rotate-then-translate is not translate-then-rotate.
        val rotZ = Mat4.fromRotationTranslation(Quat.fromAxisAngle(Vec3.UNIT_Z, quarterTurn), Vec3.ZERO)
        val shiftX = Mat4.translation(Vec3(1f, 0f, 0f))
        assertVec(Vec3(1f, 1f, 0f), (shiftX * rotZ).transformPoint(pC))
        assertVec(Vec3(0f, 2f, 0f), (rotZ * shiftX).transformPoint(pC))
        // T_a_b * T_b_a = I; the 10 m translation scales the float error, so 1e-6 relative.
        assertMat(Mat4.identity(), tAB * tAB.rigidInverse(), 1e-5f)
    }

    @Test
    fun `lookAt maps the eye to the origin looking down -Z`() {
        val view = Mat4.lookAt(Vec3(0f, 0f, 5f), Vec3.ZERO, Vec3.UNIT_Y)
        assertVec(Vec3(0f, 0f, -5f), view.transformPoint(Vec3.ZERO))
        assertVec(Vec3.ZERO, view.transformPoint(Vec3(0f, 0f, 5f)))
        assertVec(Vec3(1f, 0f, -5f), view.transformPoint(Vec3.UNIT_X))

        val side = Mat4.lookAt(Vec3(3f, 1f, 0f), Vec3(0f, 1f, 0f), Vec3.UNIT_Y)
        assertVec(Vec3(0f, 0f, -3f), side.transformPoint(Vec3(0f, 1f, 0f)))
        assertVec(Vec3(0f, 1f, 0f), side.transformPoint(Vec3(3f, 2f, 0f)))
        assertMat(Mat4.identity(), side * side.rigidInverse())
    }

    @Test
    fun `perspective maps near and far to NDC -1 and 1`() {
        val near = 0.1f
        val far = 100f
        val p = Mat4.perspective(quarterTurn, 2f, near, far)
        fun ndc(v: Vec3): Vec3 {
            val x = p[0, 0] * v.x + p[0, 1] * v.y + p[0, 2] * v.z + p[0, 3]
            val y = p[1, 0] * v.x + p[1, 1] * v.y + p[1, 2] * v.z + p[1, 3]
            val z = p[2, 0] * v.x + p[2, 1] * v.y + p[2, 2] * v.z + p[2, 3]
            val w = p[3, 0] * v.x + p[3, 1] * v.y + p[3, 2] * v.z + p[3, 3]
            return Vec3(x / w, y / w, z / w)
        }
        assertEquals(-1f, ndc(Vec3(0f, 0f, -near)).z, 1e-5f)
        assertEquals(1f, ndc(Vec3(0f, 0f, -far)).z, 1e-4f)
        // fovY 90 degrees: the top edge at depth d is y = d; aspect 2: the right edge is x = 2d.
        assertVec(Vec3(1f, 1f, ndc(Vec3(0f, 0f, -1f)).z), ndc(Vec3(2f, 1f, -1f)), 1e-5f)
    }
}
