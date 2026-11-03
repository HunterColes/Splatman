package com.huntercoles.splatman.splat.train.synthetic

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How far estimated poses are from the truth after the best similarity `x -> s R x + t` (estimated frame into the
 * truth's) fitted to the registered camera centres. NaN metrics when fewer than 3 frames are registered.
 */
data class PoseErrorReport(
    val total: Int,
    /** Frames with an estimated pose; the metrics below are over these only. */
    val registered: Int,
    val scale: Double,
    val rotation: Quat,
    val translation: Vec3,
    /** Median distance of the true camera centres (all frames) from the path's centre, truth units. */
    val pathRadius: Double,
    /** Absolute trajectory error: RMSE of the aligned camera centres, in units of [pathRadius]. */
    val ateRmse: Double,
    /** Mean angle between aligned estimated and true camera rotations, degrees. */
    val meanRotationDeg: Double,
    /** Largest such angle, degrees. */
    val maxRotationDeg: Double,
)

/** The pose error metric of #224: Horn's closed-form similarity, then ATE and rotation errors. */
object PoseErrors {
    private const val MIN_REGISTERED = 3
    private const val JACOBI_SWEEPS = 60

    /**
     * Compares [estimated] (null = not registered) with [truth], index-aligned. [centre] is the path's centre for
     * [PoseErrorReport.pathRadius] (for a scan, its subject; [SyntheticScan.subjectCentre]); null takes the centroid
     * of the true centres.
     */
    fun compare(estimated: List<CameraPose?>, truth: List<CameraPose>, centre: Vec3? = null): PoseErrorReport {
        require(estimated.size == truth.size) { "estimated has ${estimated.size} poses, truth ${truth.size}" }
        require(truth.isNotEmpty()) { "truth must not be empty" }
        val radius = pathRadius(truth, centre)
        val pairs = estimated.indices.filter { estimated[it] != null }
        val n = pairs.size
        if (n < MIN_REGISTERED) {
            return PoseErrorReport(
                truth.size, n, Double.NaN, Quat.IDENTITY, Vec3.ZERO, radius, Double.NaN, Double.NaN, Double.NaN,
            )
        }
        val a = pairs.map { v(estimated[it]!!.translationM) }
        val b = pairs.map { v(truth[it].translationM) }
        val (s, q, t) = horn(a, b)

        var sq = 0.0
        var sumDeg = 0.0
        var maxDeg = 0.0
        for ((k, i) in pairs.withIndex()) {
            val p = D.rotate(q, a[k])
            for (c in 0 until 3) {
                val d = s * p[c] + t[c] - b[k][c]
                sq += d * d
            }
            val aligned = D.mul(q, D.q(estimated[i]!!.rotation.normalized()))
            val deg = Math.toDegrees(D.angle(D.mul(D.conj(D.q(truth[i].rotation.normalized())), aligned)))
            sumDeg += deg
            maxDeg = maxOf(maxDeg, deg)
        }
        return PoseErrorReport(
            total = truth.size,
            registered = n,
            scale = s,
            rotation = D.quat(q),
            translation = Vec3(t[0].toFloat(), t[1].toFloat(), t[2].toFloat()),
            pathRadius = radius,
            ateRmse = sqrt(sq / n) / radius,
            meanRotationDeg = sumDeg / n,
            maxRotationDeg = maxDeg,
        )
    }

    private fun v(p: Vec3): DoubleArray = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())

    private fun pathRadius(truth: List<CameraPose>, centre: Vec3?): Double {
        val c = centre?.let { v(it) } ?: centroid(truth.map { v(it.translationM) })
        val d = truth.map { pose ->
            val p = v(pose.translationM)
            sqrt((0 until 3).sumOf { (p[it] - c[it]) * (p[it] - c[it]) })
        }.sorted()
        val m = d.size / 2
        return if (d.size % 2 == 1) d[m] else (d[m - 1] + d[m]) / 2
    }

    private fun centroid(p: List<DoubleArray>): DoubleArray = DoubleArray(3) { c -> p.sumOf { it[c] } / p.size }

    /**
     * Horn (1987), closed form with unit quaternions: the rotation is the top eigenvector of his 4x4 matrix N built
     * from the cross-covariance of the centred points; the scale is Umeyama's least-squares one, `sum b'.(R a') /
     * sum |a'|^2`, and `t = mean b - s R mean a`. Returns s, q (w, x, y, z), t.
     */
    private fun horn(a: List<DoubleArray>, b: List<DoubleArray>): Triple<Double, DoubleArray, DoubleArray> {
        val ca = centroid(a)
        val cb = centroid(b)
        val m = Array(3) { DoubleArray(3) } // m[i][j] = sum a'_i b'_j
        var aa = 0.0
        for (k in a.indices) {
            for (i in 0 until 3) {
                val ai = a[k][i] - ca[i]
                aa += ai * ai
                for (j in 0 until 3) m[i][j] += ai * (b[k][j] - cb[j])
            }
        }
        val (sxx, sxy, sxz) = m[0]
        val (syx, syy, syz) = m[1]
        val (szx, szy, szz) = m[2]
        val nMat = arrayOf(
            doubleArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
            doubleArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
            doubleArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
            doubleArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
        )
        val q = topEigenvector(nMat)
        var ba = 0.0
        for (k in a.indices) {
            val p = D.rotate(q, DoubleArray(3) { a[k][it] - ca[it] })
            for (i in 0 until 3) ba += (b[k][i] - cb[i]) * p[i]
        }
        val s = if (aa > 0) ba / aa else Double.NaN
        val rca = D.rotate(q, ca)
        return Triple(s, q, DoubleArray(3) { cb[it] - s * rca[it] })
    }

    /** Unit eigenvector of the largest eigenvalue of the symmetric 4x4 [m], cyclic Jacobi. */
    private fun topEigenvector(m: Array<DoubleArray>): DoubleArray {
        val a = Array(4) { m[it].copyOf() }
        val vec = Array(4) { r -> DoubleArray(4) { c -> if (r == c) 1.0 else 0.0 } }
        repeat(JACOBI_SWEEPS) {
            var off = 0.0
            for (p in 0 until 4) for (r in p + 1 until 4) off += a[p][r] * a[p][r]
            if (off < 1e-30) return@repeat
            for (p in 0 until 3) {
                for (r in p + 1 until 4) {
                    if (abs(a[p][r]) < 1e-300) continue
                    val theta = (a[r][r] - a[p][p]) / (2 * a[p][r])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val sn = t * c
                    for (k in 0 until 4) {
                        val akp = a[k][p]
                        val akr = a[k][r]
                        a[k][p] = c * akp - sn * akr
                        a[k][r] = sn * akp + c * akr
                    }
                    for (k in 0 until 4) {
                        val apk = a[p][k]
                        val ark = a[r][k]
                        a[p][k] = c * apk - sn * ark
                        a[r][k] = sn * apk + c * ark
                    }
                    for (k in 0 until 4) {
                        val vkp = vec[k][p]
                        val vkr = vec[k][r]
                        vec[k][p] = c * vkp - sn * vkr
                        vec[k][r] = sn * vkp + c * vkr
                    }
                }
            }
        }
        val best = (0 until 4).maxBy { a[it][it] }
        val q = DoubleArray(4) { vec[it][best] }
        val n = sqrt(q.sumOf { it * it })
        return DoubleArray(4) { q[it] / n }
    }
}
