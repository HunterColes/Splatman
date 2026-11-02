package com.huntercoles.splatman.splat.sh

import com.huntercoles.splatman.splat.math.Vec3

/**
 * Real spherical harmonics up to degree 3 with the basis signs and constants of the 3DGS reference
 * (`computeColorFromSH`), so coefficients from any 3DGS PLY evaluate as their trainer meant (ADR 0002, ADR 0009).
 *
 * Coefficient layout per splat, starting at `offset`: channel-major, `coeffs[offset + c * M + k]` with
 * `M = (degree + 1)^2`, channel `c` in r, g, b and `k = 0` the DC term (ADR 0002's `shDc` value of that channel
 * followed by its `shRest` coefficients).
 *
 * The result is the 3DGS colour before clamping: `0.5 + sum_k Y_k(dir) * coeff_k` per channel. Clamping is the
 * caller's (the renderer clamps to [0, 1]; the trainer needs the unclamped value for its gradient mask).
 * `dir` is the unit direction from the camera centre to the splat centre, in the frame the coefficients are in.
 */
object ShEval {
    const val C0 = 0.28209479177387814
    const val C1 = 0.4886025119029199
    val C2 = doubleArrayOf(
        1.0925484305920792, -1.0925484305920792, 0.31539156525252005, -1.0925484305920792, 0.5462742152960396,
    )
    val C3 = doubleArrayOf(
        -0.5900435899266435, 2.890611442640554, -0.4570457994644658, 0.3731763325901154,
        -0.4570457994644658, 1.445305721320277, -0.5900435899266435,
    )

    const val MAX_DEGREE = 3

    /** Coefficients per channel at [degree]: `(degree + 1)^2`. */
    fun coeffsPerChannel(degree: Int): Int {
        require(degree in 0..MAX_DEGREE) { "SH degree must be 0..$MAX_DEGREE, got $degree" }
        return (degree + 1) * (degree + 1)
    }

    /** Allocating convenience form: returns a new `FloatArray(3)` (r, g, b). */
    fun eval(degree: Int, coeffs: FloatArray, offset: Int, dir: Vec3): FloatArray {
        val out = FloatArray(3)
        eval(degree, coeffs, offset, dir.x, dir.y, dir.z, out, 0)
        return out
    }

    /** Writes r, g, b to `out[outOffset..outOffset + 2]`; allocates nothing. */
    @Suppress("LongParameterList")
    fun eval(
        degree: Int, coeffs: FloatArray, offset: Int,
        dx: Float, dy: Float, dz: Float,
        out: FloatArray, outOffset: Int,
    ) {
        val m = coeffsPerChannel(degree)
        val x = dx.toDouble(); val y = dy.toDouble(); val z = dz.toDouble()
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val yz = y * z; val xz = x * z
        for (c in 0 until 3) {
            val b = offset + c * m
            var r = C0 * coeffs[b]
            if (degree > 0) {
                r += -C1 * y * coeffs[b + 1] + C1 * z * coeffs[b + 2] - C1 * x * coeffs[b + 3]
            }
            if (degree > 1) {
                r += C2[0] * xy * coeffs[b + 4] +
                    C2[1] * yz * coeffs[b + 5] +
                    C2[2] * (2.0 * zz - xx - yy) * coeffs[b + 6] +
                    C2[3] * xz * coeffs[b + 7] +
                    C2[4] * (xx - yy) * coeffs[b + 8]
            }
            if (degree > 2) {
                r += C3[0] * y * (3.0 * xx - yy) * coeffs[b + 9] +
                    C3[1] * xy * z * coeffs[b + 10] +
                    C3[2] * y * (4.0 * zz - xx - yy) * coeffs[b + 11] +
                    C3[3] * z * (2.0 * zz - 3.0 * xx - 3.0 * yy) * coeffs[b + 12] +
                    C3[4] * x * (4.0 * zz - xx - yy) * coeffs[b + 13] +
                    C3[5] * z * (xx - yy) * coeffs[b + 14] +
                    C3[6] * x * (xx - 3.0 * yy) * coeffs[b + 15]
            }
            out[outOffset + c] = (0.5 + r).toFloat()
        }
    }
}
