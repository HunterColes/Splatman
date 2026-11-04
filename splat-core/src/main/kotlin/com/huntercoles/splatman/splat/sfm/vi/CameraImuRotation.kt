package com.huntercoles.splatman.splat.sfm.vi

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.sfm.vi.Mat3.add
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mul
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mv
import com.huntercoles.splatman.splat.sfm.vi.Mat3.sub
import com.huntercoles.splatman.splat.sfm.vi.Mat3.tmul
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The camera-IMU rotation after [CameraImuRotation.refine]. [rCS] is `R_CS` (S into C, row-major) and [rotation] the
 * same as a Hamilton quaternion. [covariance] (3x3 row-major, rad^2) is of the left perturbation `R_CS = Exp(d) R^_CS`
 * (d in C axes); null when the prior was kept. [angleFromPrior] is the angle between [rotation] and the prior, rad
 * (0 when the prior was kept). [gyroBias] (rad/s, S axes) was estimated with it. [keptPrior] is null when the fit was
 * taken, else why it was not.
 */
class CameraImuRotationFit(
    val rCS: DoubleArray,
    val rotation: Quat,
    val covariance: DoubleArray?,
    val angleFromPrior: Double,
    val usablePairs: Int,
    val gyroBias: DoubleArray,
    val keptPrior: String?,
)

/**
 * Hand-eye refinement of the camera-IMU rotation `R_CS` (ADR 0016 point 4) from its `LENS_POSE_ROTATION` prior, after
 * Qin et al. (VINS-Mono, T-RO 2018, section V-A): for every frame pair the gyro-integrated relative rotation
 * `dR_S` (S_j into S_i) and the SfM one `R_C = R_WCi^T R_WCj` satisfy `R_C = R_CS dR_S R_CS^T`.
 *
 * Unlike the paper's linear quaternion solve, the gyro bias is estimated jointly (its axis error otherwise reads as a
 * tilt of `R_CS` of `|bg| / rate`, degrees for a slow orbit): Gauss-Newton on `[d, dbg]` over the residuals
 * `Log(R_C^T R_CS dR_S(bg) R_CS^T)`, Huber-weighted (threshold [HUBER_K] times the median residual, at least
 * [HUBER_FLOOR]), from the prior. Only pairs that turn over [MIN_PAIR_ANGLE] in both measurements are used. The prior
 * is kept with fewer than [MIN_PAIRS] such pairs, when the normal equations are singular, or when the fit moves it by
 * over [MAX_MOVE].
 */
object CameraImuRotation {
    val MIN_PAIR_ANGLE: Double = Math.toRadians(5.0)
    const val MIN_PAIRS = 10
    val MAX_MOVE: Double = Math.toRadians(15.0)
    const val HUBER_K = 3.0
    val HUBER_FLOOR: Double = Math.toRadians(0.05)

    private const val ITERATIONS = 30
    private const val STEP_TOL = 1e-12
    private const val DIM = 6

    /**
     * [gyroPairs] are the preintegrations of each pair (their `deltaR` and `dRdBg`), [sfmPairs] the matching SfM
     * relative rotations `R_CiCj` (row-major). [prior] is `R_CS` as a Hamilton quaternion.
     */
    fun refine(prior: Quat, gyroPairs: List<ImuPreintegration>, sfmPairs: List<DoubleArray>): CameraImuRotationFit {
        require(gyroPairs.size == sfmPairs.size) { "gyroPairs and sfmPairs differ: ${gyroPairs.size} vs ${sfmPairs.size}" }
        val r0 = matrixOf(prior)
        val bg0 = gyroPairs.firstOrNull()?.gyroBias ?: DoubleArray(3)
        val usable = gyroPairs.indices.filter {
            angle(gyroPairs[it].deltaR) > MIN_PAIR_ANGLE && angle(sfmPairs[it]) > MIN_PAIR_ANGLE
        }
        fun keep(why: String) = CameraImuRotationFit(r0, prior, null, 0.0, usable.size, bg0.copyOf(), why)
        if (usable.size < MIN_PAIRS) return keep("${usable.size} pairs over 5 degrees, need $MIN_PAIRS")

        var r = r0
        var bg = bg0.copyOf()
        val weights = DoubleArray(usable.size) { 1.0 }
        var info: Array<DoubleArray>? = null
        var sigma2 = 0.0
        for (iteration in 0 until ITERATIONS) {
            val h = Array(DIM) { DoubleArray(DIM) }
            val b = DoubleArray(DIM)
            val norms = DoubleArray(usable.size)
            var wsq = 0.0
            for ((u, k) in usable.withIndex()) {
                val (res, jac) = residual(r, bg, gyroPairs[k], sfmPairs[k])
                norms[u] = Mat3.norm(res)
                val w = weights[u]
                wsq += w * norms[u] * norms[u]
                for (p in 0 until DIM) {
                    for (q in 0 until DIM) for (t in 0..2) h[p][q] += w * jac[t][p] * jac[t][q]
                    for (t in 0..2) b[p] -= w * jac[t][p] * res[t]
                }
            }
            sigma2 = wsq / max(1, 3 * usable.size - DIM)
            info = h
            val dx = ViDense.solveSpd(h, b) ?: return keep("singular normal equations")
            r = mul(SO3.exp(dx.copyOfRange(0, 3)), r)
            bg = add(bg, dx.copyOfRange(3, 6))
            val delta = max(HUBER_K * norms.sorted()[norms.size / 2], HUBER_FLOOR)
            for (u in norms.indices) weights[u] = if (norms[u] <= delta) 1.0 else delta / norms[u]
            if (sqrt(dx.sumOf { it * it }) < STEP_TOL && iteration > 2) break
        }
        val moved = angle(tmul(r0, r))
        if (moved > MAX_MOVE) return keep("fit moved the prior by ${Math.toDegrees(moved)} degrees, over 15")
        val inv = ViDense.inverseSpd(info!!) ?: return keep("singular normal equations")
        val cov = DoubleArray(9) { sigma2 * inv[it / 3][it % 3] }
        return CameraImuRotationFit(r, quatOf(r), cov, moved, usable.size, bg, null)
    }

    /**
     * `Log(R_C^T A)` with `A = R dR_S(bg) R^T`, and its 3x6 Jacobian against `[d, dbg]` (`R <- Exp(d) R`):
     * `Jr^-1(res) [(A^T - I) d + R Jb dbg]`, `Jb` the bias Jacobian of the corrected rotation.
     */
    private fun residual(
        r: DoubleArray,
        bg: DoubleArray,
        pre: ImuPreintegration,
        rc: DoubleArray,
    ): Pair<DoubleArray, Array<DoubleArray>> {
        val dr = pre.corrected(bg, pre.accelBias).deltaR
        val a = mul(mul(r, dr), Mat3.transpose(r))
        val res = SO3.log(tmul(rc, a))
        val jInv = SO3.rightJacobianInverse(res)
        val jd = mul(jInv, sub(Mat3.transpose(a), Mat3.I))
        val jb = mul(SO3.rightJacobian(mv(pre.dRdBg, sub(bg, pre.gyroBias))), pre.dRdBg)
        val jg = mul(jInv, mul(r, jb))
        val jac = Array(3) { t -> DoubleArray(DIM) { c -> if (c < 3) jd[3 * t + c] else jg[3 * t + c - 3] } }
        return Pair(res, jac)
    }

    private fun angle(r: DoubleArray) = Mat3.norm(SO3.log(r))

    /** Row-major rotation of a unit Hamilton quaternion (normalised first), in doubles. */
    fun matrixOf(q: Quat): DoubleArray {
        val n = q.normalized()
        val w = n.w.toDouble()
        val x = n.x.toDouble()
        val y = n.y.toDouble()
        val z = n.z.toDouble()
        val s = 1.0 / (w * w + x * x + y * y + z * z)
        return doubleArrayOf(
            1 - 2 * s * (y * y + z * z), 2 * s * (x * y - w * z), 2 * s * (x * z + w * y),
            2 * s * (x * y + w * z), 1 - 2 * s * (x * x + z * z), 2 * s * (y * z - w * x),
            2 * s * (x * z - w * y), 2 * s * (y * z + w * x), 1 - 2 * s * (x * x + y * y),
        )
    }

    /** Hamilton quaternion (w >= 0) of row-major rotation [r] (Shepperd: from the largest of w, x, y, z). */
    fun quatOf(r: DoubleArray): Quat {
        val tr = r[0] + r[4] + r[8]
        val q = when {
            tr >= r[0] && tr >= r[4] && tr >= r[8] -> {
                val s = 2 * sqrt(1 + tr)
                doubleArrayOf(s / 4, (r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s)
            }
            r[0] >= r[4] && r[0] >= r[8] -> {
                val s = 2 * sqrt(1 + r[0] - r[4] - r[8])
                doubleArrayOf((r[7] - r[5]) / s, s / 4, (r[1] + r[3]) / s, (r[2] + r[6]) / s)
            }
            r[4] >= r[8] -> {
                val s = 2 * sqrt(1 + r[4] - r[0] - r[8])
                doubleArrayOf((r[2] - r[6]) / s, (r[1] + r[3]) / s, s / 4, (r[5] + r[7]) / s)
            }
            else -> {
                val s = 2 * sqrt(1 + r[8] - r[0] - r[4])
                doubleArrayOf((r[3] - r[1]) / s, (r[2] + r[6]) / s, (r[5] + r[7]) / s, s / 4)
            }
        }
        val f = (if (q[0] < 0) -1.0 else 1.0) / sqrt(q.sumOf { it * it })
        return Quat((f * q[0]).toFloat(), (f * q[1]).toFloat(), (f * q[2]).toFloat(), (f * q[3]).toFloat())
    }
}
