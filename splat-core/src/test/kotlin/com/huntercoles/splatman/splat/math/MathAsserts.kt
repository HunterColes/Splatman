package com.huntercoles.splatman.splat.math

import org.junit.jupiter.api.Assertions.assertEquals

/** ADR 0009: float maths is checked to 1e-6. */
const val TOL = 1e-6f

fun assertVec(expected: Vec3, actual: Vec3, tol: Float = TOL) {
    assertEquals(expected.x, actual.x, tol, "x of $actual")
    assertEquals(expected.y, actual.y, tol, "y of $actual")
    assertEquals(expected.z, actual.z, tol, "z of $actual")
}

fun assertQuat(expected: Quat, actual: Quat, tol: Float = TOL) {
    assertEquals(expected.w, actual.w, tol, "w of $actual")
    assertEquals(expected.x, actual.x, tol, "x of $actual")
    assertEquals(expected.y, actual.y, tol, "y of $actual")
    assertEquals(expected.z, actual.z, tol, "z of $actual")
}

fun assertMat(expected: Mat4, actual: Mat4, tol: Float = TOL) {
    for (i in 0 until 16) assertEquals(expected.values[i], actual.values[i], tol, "index $i of $actual")
}

fun assertMat(expected: Mat3, actual: Mat3, tol: Float = TOL) {
    for (i in 0 until 9) assertEquals(expected.values[i], actual.values[i], tol, "index $i of $actual")
}

/** Row-major literal (reads like the maths) to a column-major [Mat4]. */
fun mat4Rows(vararg r: Float): Mat4 {
    require(r.size == 16)
    return Mat4(FloatArray(16) { i -> r[(i % 4) * 4 + i / 4] })
}
