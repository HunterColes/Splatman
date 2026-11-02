package com.huntercoles.splatman.splat.sh

import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.sqrt

/** ADR 0009: SH against hand-computed values, constants to 15 digits, tolerance 1e-6. */
class ShEvalTest {

    private val tol = 1e-6

    // The 3DGS constants, typed in from the reference (sh_utils.py / auxiliary.h), not read from ShEval.
    private val c0 = 0.28209479177387814
    private val c1 = 0.4886025119029199
    private val c2a = 1.0925484305920792
    private val c2c = 0.31539156525252005
    private val c2e = 0.5462742152960396
    private val c30 = 0.5900435899266435
    private val c31 = 2.890611442640554
    private val c32 = 0.4570457994644658
    private val c33 = 0.3731763325901154
    private val c35 = 1.445305721320277

    private val a = 1.0 / sqrt(3.0) // each component of (1,1,1)/sqrt3

    /**
     * Basis values Y_0..Y_15 by hand for each direction: worked from the 3DGS polynomials with x, y, z in {-1, 0, 1}
     * (or all equal to a), every nonzero term written out.
     */
    private val table: List<Pair<Vec3, DoubleArray>> = listOf(
        Vec3(1f, 0f, 0f) to doubleArrayOf(
            c0, 0.0, 0.0, -c1,
            0.0, 0.0, -c2c, 0.0, c2e,
            0.0, 0.0, 0.0, 0.0, c32, 0.0, -c30,
        ),
        Vec3(-1f, 0f, 0f) to doubleArrayOf(
            c0, 0.0, 0.0, c1,
            0.0, 0.0, -c2c, 0.0, c2e,
            0.0, 0.0, 0.0, 0.0, -c32, 0.0, c30,
        ),
        Vec3(0f, 1f, 0f) to doubleArrayOf(
            c0, -c1, 0.0, 0.0,
            0.0, 0.0, -c2c, 0.0, -c2e,
            c30, 0.0, c32, 0.0, 0.0, 0.0, 0.0,
        ),
        Vec3(0f, -1f, 0f) to doubleArrayOf(
            c0, c1, 0.0, 0.0,
            0.0, 0.0, -c2c, 0.0, -c2e,
            -c30, 0.0, -c32, 0.0, 0.0, 0.0, 0.0,
        ),
        Vec3(0f, 0f, 1f) to doubleArrayOf(
            c0, 0.0, c1, 0.0,
            0.0, 0.0, 2 * c2c, 0.0, 0.0,
            0.0, 0.0, 0.0, 2 * c33, 0.0, 0.0, 0.0,
        ),
        Vec3(0f, 0f, -1f) to doubleArrayOf(
            c0, 0.0, -c1, 0.0,
            0.0, 0.0, 2 * c2c, 0.0, 0.0,
            0.0, 0.0, 0.0, -2 * c33, 0.0, 0.0, 0.0,
        ),
        Vec3(a.toFloat(), a.toFloat(), a.toFloat()) to doubleArrayOf(
            c0, -c1 * a, c1 * a, -c1 * a,
            c2a / 3, -c2a / 3, 0.0, -c2a / 3, 0.0,
            -c30 * a * 2 / 3, c31 * a * a * a, -c32 * a * 2 / 3, -c33 * a * 4 / 3, -c32 * a * 2 / 3, 0.0,
            c30 * a * 2 / 3,
        ),
    )

    @Test
    fun `constants match the 3DGS reference to 15 digits`() {
        assertEquals(c0, ShEval.C0, 1e-15)
        assertEquals(c1, ShEval.C1, 1e-15)
        val c2 = doubleArrayOf(c2a, -c2a, c2c, -c2a, c2e)
        val c3 = doubleArrayOf(-c30, c31, -c32, c33, -c32, c35, -c30)
        for (i in c2.indices) assertEquals(c2[i], ShEval.C2[i], 1e-15, "C2[$i]")
        for (i in c3.indices) assertEquals(c3[i], ShEval.C3[i], 1e-15, "C3[$i]")
    }

    @Test
    fun `each basis function at the 6 axes and the diagonal, degrees 0 to 3`() {
        for (degree in 0..3) {
            val m = (degree + 1) * (degree + 1)
            for ((dir, y) in table) for (k in 0 until m) {
                // Unit coefficient k on red, 2 on green, -3 on blue: rgb = 0.5 + {1, 2, -3} * Y_k.
                val coeffs = FloatArray(3 * m)
                coeffs[k] = 1f
                coeffs[m + k] = 2f
                coeffs[2 * m + k] = -3f
                val rgb = ShEval.eval(degree, coeffs, 0, dir)
                assertEquals(0.5 + y[k], rgb[0].toDouble(), tol, "r, degree $degree, Y_$k at $dir")
                assertEquals(0.5 + 2 * y[k], rgb[1].toDouble(), tol, "g, degree $degree, Y_$k at $dir")
                assertEquals(0.5 - 3 * y[k], rgb[2].toDouble(), tol, "b, degree $degree, Y_$k at $dir")
            }
        }
    }

    @Test
    fun `mixed coefficients sum the basis, and only the first (degree+1)^2 per channel are read`() {
        val offset = 5
        for (degree in 0..3) {
            val m = (degree + 1) * (degree + 1)
            val coeffs = FloatArray(offset + 3 * m + 7) { 99f } // 99 marks bytes eval must not read
            for (c in 0 until 3) for (k in 0 until m) coeffs[offset + c * m + k] = 0.1f * (k + 1) * (c + 1) - 0.3f
            for ((dir, y) in table) {
                val out = FloatArray(4) { 7f }
                ShEval.eval(degree, coeffs, offset, dir.x, dir.y, dir.z, out, 1)
                assertEquals(7f, out[0])
                for (c in 0 until 3) {
                    var expected = 0.5
                    for (k in 0 until m) expected += y[k] * coeffs[offset + c * m + k]
                    assertEquals(expected, out[1 + c].toDouble(), tol, "channel $c, degree $degree at $dir")
                }
            }
        }
    }

    @Test
    fun `degree outside 0 to 3 is refused`() {
        assertThrows<IllegalArgumentException> { ShEval.eval(4, FloatArray(75), 0, Vec3.UNIT_Z) }
        assertThrows<IllegalArgumentException> { ShEval.eval(-1, FloatArray(3), 0, Vec3.UNIT_Z) }
    }
}
