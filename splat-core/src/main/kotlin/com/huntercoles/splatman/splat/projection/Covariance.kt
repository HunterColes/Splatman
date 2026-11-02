package com.huntercoles.splatman.splat.projection

import com.huntercoles.splatman.splat.math.Mat3
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3D covariance of a splat and its EWA projection to the image (ADR 0006, the pinhole of ADR 0003 in Ccv).
 *
 * A symmetric 3x3 covariance is stored as 6 floats in the 3DGS order `xx, xy, xz, yy, yz, zz`.
 * A projected 2D covariance is written as 4 floats `a, b, c, radius`: `[[a, b], [b, c]]` in px^2 (u right, v down),
 * radius = 3 sigma of the larger eigenvalue in px (uncapped; the renderer caps it).
 */
object Covariance {
    /** Splats whose centre is at or nearer than this depth (m, Ccv z) are culled. */
    const val NEAR_Z = 0.2f

    /** Low-pass filter added to the 2D diagonal (px^2), as in 3DGS. */
    const val DILATION = 0.3f

    /** Sigma radius of the screen-space footprint. */
    const val SIGMA_RADIUS = 3f

    /** Floor of the eigenvalue discriminant, as in 3DGS. */
    private const val DISC_FLOOR = 0.1

    /** `R S S^T R^T` with `S = diag(exp(logScale))`, `R` from the normalised wxyz quaternion. New `FloatArray(6)`. */
    fun world(logScale: Vec3, quat: Quat): FloatArray {
        val out = FloatArray(6)
        world(
            floatArrayOf(logScale.x, logScale.y, logScale.z), 0,
            floatArrayOf(quat.w, quat.x, quat.y, quat.z), 0,
            out, 0,
        )
        return out
    }

    /**
     * Array form: log-scales at `logScale[scaleOffset..+2]`, quaternion w, x, y, z at `quat[quatOffset..+3]` (need not
     * be unit: it is normalised, as the optimiser stores it raw), 6 floats to `out[outOffset..]`. Allocates nothing.
     */
    @Suppress("LongParameterList")
    fun world(
        logScale: FloatArray, scaleOffset: Int,
        quat: FloatArray, quatOffset: Int,
        out: FloatArray, outOffset: Int,
    ) {
        var w = quat[quatOffset].toDouble()
        var x = quat[quatOffset + 1].toDouble()
        var y = quat[quatOffset + 2].toDouble()
        var z = quat[quatOffset + 3].toDouble()
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n > 0.0) {
            w /= n; x /= n; y /= n; z /= n
        } else {
            w = 1.0
        }
        val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - w * z); val r02 = 2 * (x * z + w * y)
        val r10 = 2 * (x * y + w * z); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - w * x)
        val r20 = 2 * (x * z - w * y); val r21 = 2 * (y * z + w * x); val r22 = 1 - 2 * (x * x + y * y)
        // M = R S (column j of R scaled by s_j); Sigma = M M^T.
        val s0 = exp(logScale[scaleOffset].toDouble())
        val s1 = exp(logScale[scaleOffset + 1].toDouble())
        val s2 = exp(logScale[scaleOffset + 2].toDouble())
        val m00 = r00 * s0; val m01 = r01 * s1; val m02 = r02 * s2
        val m10 = r10 * s0; val m11 = r11 * s1; val m12 = r12 * s2
        val m20 = r20 * s0; val m21 = r21 * s1; val m22 = r22 * s2
        out[outOffset] = (m00 * m00 + m01 * m01 + m02 * m02).toFloat()
        out[outOffset + 1] = (m00 * m10 + m01 * m11 + m02 * m12).toFloat()
        out[outOffset + 2] = (m00 * m20 + m01 * m21 + m02 * m22).toFloat()
        out[outOffset + 3] = (m10 * m10 + m11 * m11 + m12 * m12).toFloat()
        out[outOffset + 4] = (m10 * m20 + m11 * m21 + m12 * m22).toFloat()
        out[outOffset + 5] = (m20 * m20 + m21 * m21 + m22 * m22).toFloat()
    }

    /**
     * EWA projection: `Sigma2D = J W Sigma W^T J^T + 0.3 I`, with `W` = [rotWorldToCv] and `J` the Jacobian of
     * `u = fx X / Z, v = fy Y / Z` at the centre (`meanCv[meanOffset..+2]`, already in Ccv, metres).
     * Writes `a, b, c, radius` to `out[outOffset..+3]` and returns [out], or returns null (out untouched) when the
     * centre's z <= [NEAR_Z] (too near, behind the camera, or NaN). Allocates nothing.
     */
    @Suppress("LongParameterList")
    fun project(
        meanCv: FloatArray, meanOffset: Int,
        cov6: FloatArray, covOffset: Int,
        rotWorldToCv: Mat3, fx: Float, fy: Float,
        out: FloatArray, outOffset: Int = 0,
    ): FloatArray? {
        val tz = meanCv[meanOffset + 2].toDouble()
        if (!(tz > NEAR_Z)) return null
        val tx = meanCv[meanOffset].toDouble()
        val ty = meanCv[meanOffset + 1].toDouble()
        val j00 = fx / tz; val j02 = -fx * tx / (tz * tz)
        val j11 = fy / tz; val j12 = -fy * ty / (tz * tz)
        val w = rotWorldToCv.values // column-major: (row r, column c) = w[c * 3 + r]
        // T = J W (2x3); J's row 0 is (j00, 0, j02), row 1 is (0, j11, j12).
        val t00 = j00 * w[0] + j02 * w[2]; val t01 = j00 * w[3] + j02 * w[5]; val t02 = j00 * w[6] + j02 * w[8]
        val t10 = j11 * w[1] + j12 * w[2]; val t11 = j11 * w[4] + j12 * w[5]; val t12 = j11 * w[7] + j12 * w[8]
        val sxx = cov6[covOffset].toDouble(); val sxy = cov6[covOffset + 1].toDouble()
        val sxz = cov6[covOffset + 2].toDouble(); val syy = cov6[covOffset + 3].toDouble()
        val syz = cov6[covOffset + 4].toDouble(); val szz = cov6[covOffset + 5].toDouble()
        // Rows of T Sigma.
        val u0 = t00 * sxx + t01 * sxy + t02 * sxz
        val u1 = t00 * sxy + t01 * syy + t02 * syz
        val u2 = t00 * sxz + t01 * syz + t02 * szz
        val v0 = t10 * sxx + t11 * sxy + t12 * sxz
        val v1 = t10 * sxy + t11 * syy + t12 * syz
        val v2 = t10 * sxz + t11 * syz + t12 * szz
        val a = u0 * t00 + u1 * t01 + u2 * t02 + DILATION
        val b = u0 * t10 + u1 * t11 + u2 * t12
        val c = v0 * t10 + v1 * t11 + v2 * t12 + DILATION
        val mid = 0.5 * (a + c)
        val det = a * c - b * b
        val lambdaMax = mid + sqrt(max(DISC_FLOOR, mid * mid - det))
        out[outOffset] = a.toFloat()
        out[outOffset + 1] = b.toFloat()
        out[outOffset + 2] = c.toFloat()
        out[outOffset + 3] = (SIGMA_RADIUS * sqrt(lambdaMax)).toFloat()
        return out
    }
}
