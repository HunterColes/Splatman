package com.huntercoles.splatman.splat.sfm.eval

import kotlin.math.abs
import kotlin.math.sqrt

/** Absolute trajectory error after a closed-form Umeyama (1991) alignment of the estimate onto ground truth. */
object Ate {
    enum class Align { SE3, SIM3 }

    data class Pair(val est: TumPose, val gt: TumPose)

    /** [rotation] is row-major 3x3; the alignment maps an estimate point p to scale * R * p + translation. */
    class Result(
        val rmse: Double,
        val mean: Double,
        val median: Double,
        val max: Double,
        val scale: Double,
        val rotation: DoubleArray,
        val translation: DoubleArray,
        val pairs: Int,
    )

    /** Each estimate pose paired with the unused ground-truth pose nearest in time, if within [maxDtS]. */
    fun associate(est: List<TumPose>, gt: List<TumPose>, maxDtS: Double = 0.02): List<Pair> {
        val g = gt.sortedBy { it.t }
        val times = DoubleArray(g.size) { g[it].t }
        val used = BooleanArray(g.size)
        val out = ArrayList<Pair>()
        for (e in est.sortedBy { it.t }) {
            val k = times.binarySearch(e.t).let { if (it < 0) -it - 1 else it }
            var best = -1
            var bestDt = Double.MAX_VALUE
            for (j in intArrayOf(k - 1, k, k + 1)) {
                if (j < 0 || j >= g.size || used[j]) continue
                val dt = abs(g[j].t - e.t)
                if (dt < bestDt) {
                    bestDt = dt
                    best = j
                }
            }
            if (best >= 0 && bestDt <= maxDtS) {
                used[best] = true
                out += Pair(e, g[best])
            }
        }
        return out
    }

    fun score(pairs: List<Pair>, align: Align = Align.SE3): Result {
        require(pairs.size >= 3) { "ATE needs at least 3 pairs, got ${pairs.size}" }
        val n = pairs.size
        val x = Array(n) { doubleArrayOf(pairs[it].est.tx, pairs[it].est.ty, pairs[it].est.tz) }
        val y = Array(n) { doubleArrayOf(pairs[it].gt.tx, pairs[it].gt.ty, pairs[it].gt.tz) }
        val mx = DoubleArray(3)
        val my = DoubleArray(3)
        for (i in 0 until n) for (a in 0..2) {
            mx[a] += x[i][a] / n
            my[a] += y[i][a] / n
        }
        val cov = DoubleArray(9)
        var varX = 0.0
        for (i in 0 until n) for (a in 0..2) {
            val dy = y[i][a] - my[a]
            for (b in 0..2) cov[a * 3 + b] += dy * (x[i][b] - mx[b]) / n
            val dx = x[i][a] - mx[a]
            varX += dx * dx / n
        }
        val svd = Svd3.of(cov)
        if (align == Align.SIM3) {
            require(svd.s[1] > 1e-9 * svd.s[0] && svd.s[0] > 0.0) {
                "degenerate trajectory for SIM3: points are collinear or coincident " +
                    "(cross-covariance singular values ${svd.s.toList()})"
            }
        }
        val sign = if (det(svd.u) * det(svd.v) < 0) -1.0 else 1.0
        val d = doubleArrayOf(1.0, 1.0, sign)
        val r = DoubleArray(9)
        for (a in 0..2) for (b in 0..2) {
            var s = 0.0
            for (k in 0..2) s += svd.u[a * 3 + k] * d[k] * svd.v[b * 3 + k]
            r[a * 3 + b] = s
        }
        val c = if (align == Align.SIM3) (svd.s[0] + svd.s[1] + sign * svd.s[2]) / varX else 1.0
        val t = DoubleArray(3) { a -> my[a] - c * (r[a * 3] * mx[0] + r[a * 3 + 1] * mx[1] + r[a * 3 + 2] * mx[2]) }
        val err = DoubleArray(n) { i ->
            var s = 0.0
            for (a in 0..2) {
                val p = c * (r[a * 3] * x[i][0] + r[a * 3 + 1] * x[i][1] + r[a * 3 + 2] * x[i][2]) + t[a]
                val e = p - y[i][a]
                s += e * e
            }
            sqrt(s)
        }
        val sorted = err.sorted()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        return Result(
            rmse = sqrt(err.sumOf { it * it } / n),
            mean = err.average(),
            median = median,
            max = sorted.last(),
            scale = c,
            rotation = r,
            translation = t,
            pairs = n,
        )
    }

    internal fun det(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
}

/** SVD of a 3x3 row-major matrix A = U diag(s) V^T, s descending; U, V orthogonal with singular vectors as columns. */
internal class Svd3(val u: DoubleArray, val s: DoubleArray, val v: DoubleArray) {
    companion object {
        private val PAIRS = arrayOf(intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 2))

        fun of(a: DoubleArray): Svd3 {
            // Eigen-decompose A^T A by cyclic Jacobi: A^T A = V diag(s^2) V^T.
            val b = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += a[k * 3 + i] * a[k * 3 + j]
                b[i * 3 + j] = s
            }
            val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            repeat(60) {
                if (b[1] * b[1] + b[2] * b[2] + b[5] * b[5] == 0.0) return@repeat
                for (pq in PAIRS) rotate(b, v, pq[0], pq[1])
            }
            val order = (0..2).sortedByDescending { b[it * 3 + it] }
            val vs = DoubleArray(9)
            for (c in 0..2) for (k in 0..2) vs[k * 3 + c] = v[k * 3 + order[c]]
            val s = DoubleArray(3)
            val u = DoubleArray(9)
            for (c in 0..2) {
                for (r in 0..2) u[r * 3 + c] = a[r * 3] * vs[c] + a[r * 3 + 1] * vs[3 + c] + a[r * 3 + 2] * vs[6 + c]
                s[c] = sqrt((0..2).sumOf { u[it * 3 + c] * u[it * 3 + c] })
            }
            val tol = 1e-12 * s[0]
            for (c in 0..2) {
                if (s[c] > tol && s[c] > 0.0) {
                    orthonormalise(u, c)
                } else {
                    s[c] = 0.0
                    fillOrthogonal(u, c)
                }
            }
            return Svd3(u, s, vs)
        }

        private fun rotate(b: DoubleArray, v: DoubleArray, p: Int, q: Int) {
            val bpq = b[p * 3 + q]
            if (bpq == 0.0) return
            val theta = (b[q * 3 + q] - b[p * 3 + p]) / (2 * bpq)
            val tt = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val cs = 1 / sqrt(tt * tt + 1)
            val sn = tt * cs
            for (k in 0..2) {
                val bkp = b[k * 3 + p]
                val bkq = b[k * 3 + q]
                b[k * 3 + p] = cs * bkp - sn * bkq
                b[k * 3 + q] = sn * bkp + cs * bkq
            }
            for (k in 0..2) {
                val bpk = b[p * 3 + k]
                val bqk = b[q * 3 + k]
                b[p * 3 + k] = cs * bpk - sn * bqk
                b[q * 3 + k] = sn * bpk + cs * bqk
            }
            b[p * 3 + q] = 0.0
            b[q * 3 + p] = 0.0
            for (k in 0..2) {
                val vkp = v[k * 3 + p]
                val vkq = v[k * 3 + q]
                v[k * 3 + p] = cs * vkp - sn * vkq
                v[k * 3 + q] = sn * vkp + cs * vkq
            }
        }

        /** Gram-Schmidt column c of [u] against columns before it, then normalise. */
        private fun orthonormalise(u: DoubleArray, c: Int): Boolean {
            for (p in 0 until c) {
                val dot = (0..2).sumOf { u[it * 3 + c] * u[it * 3 + p] }
                for (r in 0..2) u[r * 3 + c] -= dot * u[r * 3 + p]
            }
            val nn = sqrt((0..2).sumOf { u[it * 3 + c] * u[it * 3 + c] })
            if (nn < 1e-6) return false
            for (r in 0..2) u[r * 3 + c] /= nn
            return true
        }

        /** Column c of [u] := a unit vector orthogonal to the columns before it. */
        private fun fillOrthogonal(u: DoubleArray, c: Int) {
            for (axis in 0..2) {
                for (r in 0..2) u[r * 3 + c] = if (r == axis) 1.0 else 0.0
                if (orthonormalise(u, c)) return
            }
        }
    }
}
