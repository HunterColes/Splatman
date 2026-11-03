package com.huntercoles.splatman.splat.sfm.vi

import kotlin.math.abs
import kotlin.math.max

/**
 * Central finite differences for checking analytic Jacobians (ADR 0016 point 8), double precision. A Jacobian is
 * `Array(rows) { DoubleArray(cols) }`: `J[i][j] = d f_i / d x_j`.
 */
object NumericJacobian {
    /** `J[i][j] = (f(x + h e_j) - f(x - h e_j))_i / 2h`; [x] is not modified. */
    fun central(f: (DoubleArray) -> DoubleArray, x: DoubleArray, h: Double = 1e-6): Array<DoubleArray> =
        manifold(f, x, x.size, { base, d -> DoubleArray(base.size) { base[it] + d[it] } }, h)

    /**
     * Jacobian of `f` at [x] on a manifold with a [dim]-vector tangent: `J[i][j] = (f(x [+] h e_j) - f(x [+] -h e_j))_i
     * / 2h`, where [plus] is the retraction `x [+] delta`.
     */
    fun <T> manifold(
        f: (T) -> DoubleArray,
        x: T,
        dim: Int,
        plus: (T, DoubleArray) -> T,
        h: Double = 1e-6,
    ): Array<DoubleArray> {
        val cols = (0 until dim).map { j ->
            val d = DoubleArray(dim)
            d[j] = h
            val fp = f(plus(x, d))
            d[j] = -h
            val fm = f(plus(x, d))
            DoubleArray(fp.size) { (fp[it] - fm[it]) / (2 * h) }
        }
        val rows = cols.firstOrNull()?.size ?: f(x).size
        return Array(rows) { i -> DoubleArray(dim) { j -> cols[j][i] } }
    }

    /** Jacobian with respect to a rotation `R` (row-major 3x3) perturbed on the right: `R exp(delta)`. */
    fun rotationRight(f: (DoubleArray) -> DoubleArray, r: DoubleArray, h: Double = 1e-6): Array<DoubleArray> =
        manifold(f, r, 3, { base, d -> M3.mul(base, M3.exp(d)) }, h)
}

/**
 * Fails unless [analytic] and [numeric] have the same shape and every entry agrees:
 * `|a - n| <= relTol * max(|a|, |n|, absFloor)`. The floor keeps entries that should be zero from failing on
 * finite-difference round-off.
 */
fun assertJacobian(
    analytic: Array<DoubleArray>,
    numeric: Array<DoubleArray>,
    relTol: Double = 1e-4,
    absFloor: Double = 1e-3,
) {
    if (analytic.size != numeric.size) {
        throw AssertionError("Jacobian rows: analytic ${analytic.size}, numeric ${numeric.size}")
    }
    for (i in analytic.indices) {
        if (analytic[i].size != numeric[i].size) {
            throw AssertionError("Jacobian row $i columns: analytic ${analytic[i].size}, numeric ${numeric[i].size}")
        }
        for (j in analytic[i].indices) {
            val a = analytic[i][j]
            val n = numeric[i][j]
            val tol = relTol * max(max(abs(a), abs(n)), absFloor)
            if (!(abs(a - n) <= tol)) {
                throw AssertionError("Jacobian [$i][$j]: analytic $a, numeric $n, |diff| ${abs(a - n)} > $tol")
            }
        }
    }
}
