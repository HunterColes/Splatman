package com.huntercoles.splatman.splat.sfm.vi

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SO(3) in doubles (Forster et al., T-RO 2017, section III): rotations are row-major 3x3 DoubleArrays, tangent vectors
 * 3-vectors. `Exp(w + d) ~ Exp(w) Exp(Jr(w) d)` and `Log(Exp(w) Exp(d)) ~ w + Jr^-1(w) d`.
 *
 * Below [SMALL_ANGLE] every closed form switches to its Taylor series to second order in theta, where the closed
 * forms lose digits to cancellation (the next series term is under 1e-17 there).
 */
object SO3 {
    const val SMALL_ANGLE = 1e-4

    /** Near pi the skew part of R vanishes; below this cosine the axis comes from the symmetric part instead. */
    private const val NEAR_PI_COS = -0.9

    /** Rodrigues: `I + sin(t)/t [w]x + (1 - cos t)/t^2 [w]x^2`. */
    fun exp(w: DoubleArray): DoubleArray {
        val t2 = Mat3.dot(w, w)
        val th = sqrt(t2)
        val a = if (th < SMALL_ANGLE) 1.0 - t2 / 6 else sin(th) / th
        val b = if (th < SMALL_ANGLE) 0.5 - t2 / 24 else (1 - cos(th)) / t2
        return Mat3.combine(a, b, w)
    }

    /** The rotation vector of [r], angle in [0, pi]; at exactly pi either sign of the axis is returned. */
    fun log(r: DoubleArray): DoubleArray {
        val vee = doubleArrayOf((r[7] - r[5]) / 2, (r[2] - r[6]) / 2, (r[3] - r[1]) / 2)
        val s = Mat3.norm(vee)
        val c = ((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)
        val th = atan2(s, c)
        if (c > NEAR_PI_COS) {
            val f = if (th < SMALL_ANGLE) 1.0 + th * th / 6 else th / s
            return Mat3.scale(vee, f)
        }
        // (R + R^T) / 2 - cos(t) I = (1 - cos t) n n^T: take the column with the largest diagonal, sign from the skew part.
        val m = DoubleArray(9) { (r[it] + r[(it % 3) * 3 + it / 3]) / 2 - if (it % 4 == 0) c else 0.0 }
        val k = (0..2).maxBy { m[4 * it] }
        val col = doubleArrayOf(m[k], m[3 + k], m[6 + k])
        val n = Mat3.scale(col, 1.0 / Mat3.norm(col))
        val sign = if (Mat3.dot(n, vee) < 0) -1.0 else 1.0
        return Mat3.scale(n, sign * th)
    }

    /** `Jr(w) = I - (1 - cos t)/t^2 [w]x + (t - sin t)/t^3 [w]x^2`. */
    fun rightJacobian(w: DoubleArray): DoubleArray {
        val t2 = Mat3.dot(w, w)
        val th = sqrt(t2)
        val a = if (th < SMALL_ANGLE) 0.5 - t2 / 24 else (1 - cos(th)) / t2
        val b = if (th < SMALL_ANGLE) 1.0 / 6 - t2 / 120 else (th - sin(th)) / (t2 * th)
        return Mat3.combine(-a, b, w)
    }

    /** `Jr^-1(w) = I + 1/2 [w]x + (1/t^2 - (1 + cos t)/(2 t sin t)) [w]x^2`, for angles below pi. */
    fun rightJacobianInverse(w: DoubleArray): DoubleArray {
        val t2 = Mat3.dot(w, w)
        val th = sqrt(t2)
        val b = if (th < SMALL_ANGLE) 1.0 / 12 + t2 / 720 else 1 / t2 - (1 + cos(th)) / (2 * th * sin(th))
        return Mat3.combine(0.5, b, w)
    }
}

/** Row-major 3x3 and 3-vector helpers for the VIO maths, double precision. */
internal object Mat3 {
    val I: DoubleArray get() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** `I + a [w]x + b [w]x^2`. */
    fun combine(a: Double, b: Double, w: DoubleArray): DoubleArray {
        val k = skew(w)
        val k2 = mul(k, k)
        return DoubleArray(9) { (if (it % 4 == 0) 1.0 else 0.0) + a * k[it] + b * k2[it] }
    }

    fun skew(w: DoubleArray) = doubleArrayOf(0.0, -w[2], w[1], w[2], 0.0, -w[0], -w[1], w[0], 0.0)

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3
        val j = k % 3
        a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j]
    }

    /** `a^T b`. */
    fun tmul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3
        val j = k % 3
        a[i] * b[j] + a[3 + i] * b[3 + j] + a[6 + i] * b[6 + j]
    }

    fun transpose(a: DoubleArray) = DoubleArray(9) { k -> a[(k % 3) * 3 + k / 3] }
    fun mv(a: DoubleArray, v: DoubleArray) =
        DoubleArray(3) { i -> a[3 * i] * v[0] + a[3 * i + 1] * v[1] + a[3 * i + 2] * v[2] }
    fun mtv(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> a[i] * v[0] + a[3 + i] * v[1] + a[6 + i] * v[2] }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] - b[it] }
    fun scale(a: DoubleArray, s: Double) = DoubleArray(a.size) { a[it] * s }
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun norm(v: DoubleArray) = sqrt(dot(v, v))
    fun maxAbs(a: DoubleArray) = a.maxOf { abs(it) }
}
