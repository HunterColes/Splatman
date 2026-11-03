package com.huntercoles.splatman.splat.sfm.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs

class SO3Test {
    private val rnd = Random(364)

    /** A random rotation vector with angle in [lo, hi). */
    private fun randomVector(lo: Double, hi: Double): DoubleArray {
        val d = DoubleArray(3) { rnd.nextGaussian() }
        return M3.scale(d, (lo + (hi - lo) * rnd.nextDouble()) / M3.norm(d))
    }

    private fun assertClose(expected: DoubleArray, actual: DoubleArray, tol: Double, what: String) {
        val diff = M3.maxAbsDiff(expected, actual)
        assertTrue(diff <= tol, "$what: max |diff| $diff > $tol")
    }

    private fun rows(m: DoubleArray) = Array(3) { i -> m.copyOfRange(3 * i, 3 * i + 3) }

    @Test
    fun `exp matches the reference Rodrigues and is a rotation`() {
        repeat(50) {
            val w = randomVector(0.0, PI)
            val r = SO3.exp(w)
            assertClose(M3.exp(w), r, 1e-14, "exp")
            assertClose(M3.I, M3.mul(M3.transpose(r), r), 1e-14, "R^T R")
        }
    }

    @Test
    fun `log inverts exp at every angle below pi`() {
        repeat(200) {
            val w = randomVector(0.0, PI - 1e-6)
            assertClose(w, SO3.log(SO3.exp(w)), 1e-9, "log(exp(w)) at |w| ${M3.norm(w)}")
        }
        for (angle in doubleArrayOf(0.5, 2.0, 2.7, 3.0, PI - 1e-3, PI - 1e-7)) {
            val w = randomVector(angle, angle)
            assertClose(w, SO3.log(SO3.exp(w)), 1e-8, "log(exp(w)) at $angle")
        }
    }

    @Test
    fun `log at exactly pi returns pi about the axis, either sign`() {
        val n = M3.scale(doubleArrayOf(1.0, -2.0, 0.5), 1.0 / M3.norm(doubleArrayOf(1.0, -2.0, 0.5)))
        val w = SO3.log(SO3.exp(M3.scale(n, PI)))
        assertEquals(PI, M3.norm(w), 1e-12)
        assertClose(SO3.exp(M3.scale(n, PI)), SO3.exp(w), 1e-12, "exp(log(R)) at pi")
    }

    @Test
    fun `small-angle branches below 1e-8 rad are first order and exact at zero`() {
        assertClose(M3.I, SO3.exp(DoubleArray(3)), 0.0, "exp(0)")
        assertClose(DoubleArray(3), SO3.log(M3.I), 0.0, "log(I)")
        assertClose(M3.I, SO3.rightJacobian(DoubleArray(3)), 0.0, "Jr(0)")
        assertClose(M3.I, SO3.rightJacobianInverse(DoubleArray(3)), 0.0, "Jr^-1(0)")
        for (angle in doubleArrayOf(1e-9, 3e-12, 1e-15)) {
            val w = randomVector(angle, angle)
            val k = M3.skew(w)
            assertClose(M3.add(M3.I, k), SO3.exp(w), 1e-16, "exp at $angle")
            assertClose(w, SO3.log(SO3.exp(w)), 1e-22, "log at $angle")
            assertClose(M3.sub(M3.I, M3.scale(k, 0.5)), SO3.rightJacobian(w), 1e-16, "Jr at $angle")
            assertClose(M3.add(M3.I, M3.scale(k, 0.5)), SO3.rightJacobianInverse(w), 1e-16, "Jr^-1 at $angle")
        }
    }

    @Test
    fun `series and closed forms agree across the small-angle switch`() {
        val n = M3.scale(doubleArrayOf(0.3, 0.4, -0.2), 1.0 / M3.norm(doubleArrayOf(0.3, 0.4, -0.2)))
        val below = M3.scale(n, SO3.SMALL_ANGLE * (1 - 1e-9))
        val above = M3.scale(n, SO3.SMALL_ANGLE * (1 + 1e-9))
        // The two arguments differ by 2e-13 and the closed forms lose about 1e-12 to cancellation there; a wrong series
        // coefficient shows up at 1e-9 or more.
        assertClose(SO3.exp(below), SO3.exp(above), 2e-12, "exp")
        assertClose(SO3.rightJacobian(below), SO3.rightJacobian(above), 2e-12, "Jr")
        assertClose(SO3.rightJacobianInverse(below), SO3.rightJacobianInverse(above), 2e-12, "Jr^-1")
        assertClose(below, SO3.log(SO3.exp(below)), 1e-18, "log below")
        assertClose(above, SO3.log(SO3.exp(above)), 1e-18, "log above")
    }

    @Test
    fun `right Jacobian matches finite differences of Exp(w + d) = Exp(w) Exp(Jr d)`() {
        for (angle in doubleArrayOf(1e-3, 0.4, 1.5, 2.8)) {
            val w = randomVector(angle, angle)
            val base = SO3.exp(w)
            val numeric = NumericJacobian.central(
                { d -> SO3.log(M3.mul(M3.transpose(base), SO3.exp(M3.add(w, d)))) },
                DoubleArray(3),
            )
            assertJacobian(rows(SO3.rightJacobian(w)), numeric)
        }
    }

    @Test
    fun `inverse right Jacobian matches finite differences of Log(R Exp(d)) and inverts Jr`() {
        for (angle in doubleArrayOf(1e-3, 0.4, 1.5, 2.8)) {
            val w = randomVector(angle, angle)
            val numeric = NumericJacobian.rotationRight({ r -> SO3.log(r) }, SO3.exp(w))
            assertJacobian(rows(SO3.rightJacobianInverse(w)), numeric)
            assertClose(M3.I, M3.mul(SO3.rightJacobian(w), SO3.rightJacobianInverse(w)), 1e-12, "Jr Jr^-1 at $angle")
        }
    }

    @Test
    fun `log at a near-pi rotation keeps the angle`() {
        val w = randomVector(PI - 1e-5, PI - 1e-5)
        assertTrue(abs(M3.norm(SO3.log(SO3.exp(w))) - (PI - 1e-5)) < 1e-10)
    }
}
