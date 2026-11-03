package com.huntercoles.splatman.splat.sfm

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What [BundleAdjuster.adjust] returns: a new [model] (the input is untouched), the mean and median reprojection error
 * in pixels over every input observation ("before") and over the returned model's kept observations ("after"), and
 * the iterations run.
 */
class BaResult(
    val model: SparseModel,
    val meanBeforePx: Float,
    val medianBeforePx: Float,
    val meanAfterPx: Float,
    val medianAfterPx: Float,
    val iterations: Int,
)

/**
 * Bundle adjustment of a [SparseModel] in pure Kotlin (#227, epic #200, ADR 0012): every registered camera and every
 * point move to minimise the reprojection error, OpenCV axes in and out (ADR 0003): `x_c = R x_w + t`,
 * `u = fx x/z + cx`, `v = fy y/z + cy`, with each frame's own intrinsics, held fixed.
 *
 * Levenberg-Marquardt (Marquardt diagonal scaling) on a Huber loss at [HUBER_PX] (iteratively reweighted). A camera is
 * its rotation, updated locally `R <- exp([w]x) R` (so(3) on the left), and its centre `C` (`t = -R C`). The gauge is
 * fixed by holding the first registered camera and the distance from it to the second: the second camera's centre
 * lives on that sphere (two tangent parameters), so the reduced camera system has 5 + 6 (n - 2) unknowns. The points
 * are eliminated with the Schur complement (3x3 blocks), and the reduced camera system is solved by a dense Cholesky.
 *
 * Doubles inside; every buffer is allocated once per call, so an iteration allocates nothing per observation. Stops on
 * a relative cost change under [REL_COST_TOL], the iteration cap, or [isCancelled] (polled once at the top of every
 * iteration). Then observations with an error over [DROP_PX] are dropped and points left with fewer than 2 removed;
 * when that dropped anything and iterations remain, the adjustment runs again on what is left (at most
 * [MAX_ROUNDS] rounds), and the drop is always the last step.
 */
object BundleAdjuster {

    const val HUBER_PX = 2.0
    const val DROP_PX = 4.0
    const val REL_COST_TOL = 1e-6
    private const val MAX_ROUNDS = 3
    private const val MAX_TRIALS = 10
    private const val LAMBDA_START = 1e-4
    private const val MIN_Z = 1e-9
    private const val BEHIND_PX = 1e6

    fun adjust(model: SparseModel, frames: List<SfmFrame>, iterations: Int, isCancelled: () -> Boolean): BaResult {
        require(iterations >= 0) { "iterations must be >= 0, got $iterations" }
        require(frames.size == model.frameCount) {
            "frames must hold ${model.frameCount} entries (one per model frame), got ${frames.size}"
        }
        val before = reprojectionErrors(model, frames)
        val problem = Problem(model, frames)
        var used = 0
        var round = 0
        while (true) {
            used += problem.optimise(iterations - used, isCancelled)
            val dropped = problem.dropOutliers()
            if (problem.cancelled || dropped == 0 || used >= iterations || ++round >= MAX_ROUNDS) break
        }
        val out = problem.toModel()
        val after = reprojectionErrors(out, frames)
        return BaResult(
            out,
            mean(before).toFloat(),
            median(before).toFloat(),
            mean(after).toFloat(),
            median(after).toFloat(),
            used,
        )
    }

    /** Pixel error of every observation of [model]; +inf for a point on or behind its camera. */
    internal fun reprojectionErrors(model: SparseModel, frames: List<SfmFrame>): DoubleArray {
        val nf = model.frameCount
        val r = DoubleArray(9 * nf)
        for (f in 0 until nf) if (model.registered[f]) quatToMat(model.rotationsCcvW, 4 * f, r, 9 * f)
        val out = DoubleArray(model.observationCount)
        for (o in out.indices) {
            val f = model.obsFrame[o]
            val p = model.obsPoint[o]
            val x = model.points[3 * p].toDouble()
            val y = model.points[3 * p + 1].toDouble()
            val z = model.points[3 * p + 2].toDouble()
            val b = 9 * f
            val cx = r[b] * x + r[b + 1] * y + r[b + 2] * z + model.translationsCcvW[3 * f]
            val cy = r[b + 3] * x + r[b + 4] * y + r[b + 5] * z + model.translationsCcvW[3 * f + 1]
            val cz = r[b + 6] * x + r[b + 7] * y + r[b + 8] * z + model.translationsCcvW[3 * f + 2]
            out[o] = if (cz <= 0) {
                Double.POSITIVE_INFINITY
            } else {
                val k = frames[f].intrinsics
                val du = k.fx * cx / cz + k.cx - model.obsUv[2 * o]
                val dv = k.fy * cy / cz + k.cy - model.obsUv[2 * o + 1]
                sqrt(du * du + dv * dv)
            }
        }
        return out
    }

    private fun mean(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.sum() / x.size

    private fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** Normalised quaternion w, x, y, z at [q] + [qo] to a row-major rotation at [r] + [ro], in Double. */
    private fun quatToMat(q: FloatArray, qo: Int, r: DoubleArray, ro: Int) {
        var w = q[qo].toDouble()
        var x = q[qo + 1].toDouble()
        var y = q[qo + 2].toDouble()
        var z = q[qo + 3].toDouble()
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n > 0) {
            w /= n; x /= n; y /= n; z /= n
        } else {
            w = 1.0
        }
        r[ro] = 1 - 2 * (y * y + z * z)
        r[ro + 1] = 2 * (x * y - w * z)
        r[ro + 2] = 2 * (x * z + w * y)
        r[ro + 3] = 2 * (x * y + w * z)
        r[ro + 4] = 1 - 2 * (x * x + z * z)
        r[ro + 5] = 2 * (y * z - w * x)
        r[ro + 6] = 2 * (x * z - w * y)
        r[ro + 7] = 2 * (y * z + w * x)
        r[ro + 8] = 1 - 2 * (x * x + y * y)
    }

    /** Row-major rotation at [m] + [mo] to a unit quaternion w, x, y, z written as Floats at [q] + [qo] (Shepperd). */
    private fun matToQuat(m: DoubleArray, mo: Int, q: FloatArray, qo: Int) {
        val m00 = m[mo]; val m01 = m[mo + 1]; val m02 = m[mo + 2]
        val m10 = m[mo + 3]; val m11 = m[mo + 4]; val m12 = m[mo + 5]
        val m20 = m[mo + 6]; val m21 = m[mo + 7]; val m22 = m[mo + 8]
        val tr = m00 + m11 + m22
        var w: Double; var x: Double; var y: Double; var z: Double
        if (tr > 0) {
            val s = sqrt(tr + 1) * 2
            w = s / 4; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1 + m00 - m11 - m22) * 2
            w = (m21 - m12) / s; x = s / 4; y = (m01 + m10) / s; z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1 + m11 - m00 - m22) * 2
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = s / 4; z = (m12 + m21) / s
        } else {
            val s = sqrt(1 + m22 - m00 - m11) * 2
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = s / 4
        }
        val n = sqrt(w * w + x * x + y * y + z * z)
        q[qo] = (w / n).toFloat(); q[qo + 1] = (x / n).toFloat(); q[qo + 2] = (y / n).toFloat(); q[qo + 3] = (z / n).toFloat()
    }

    /** The working state of one call: parameters, observations, and every scratch buffer, allocated once. */
    private class Problem(private val input: SparseModel, frames: List<SfmFrame>) {
        private val nf = input.frameCount
        private val np = input.pointCount
        private val no0 = input.observationCount

        private val fx = DoubleArray(nf) { frames[it].intrinsics.fx.toDouble() }
        private val fy = DoubleArray(nf) { frames[it].intrinsics.fy.toDouble() }
        private val cx = DoubleArray(nf) { frames[it].intrinsics.cx.toDouble() }
        private val cy = DoubleArray(nf) { frames[it].intrinsics.cy.toDouble() }

        // Parameters (current and candidate): rotations row-major 9 per frame, centres 3 per frame, points 3 each.
        private var rot = DoubleArray(9 * nf)
        private var cen = DoubleArray(3 * nf)
        private var pts = DoubleArray(3 * np) { input.points[it].toDouble() }
        private var rotN = DoubleArray(9 * nf)
        private var cenN = DoubleArray(3 * nf)
        private var ptsN = DoubleArray(3 * np)

        // Gauge: the first registered frame is fixed; the second's centre is first + dist * u, u on the unit sphere.
        private val first = input.registered.indexOfFirst { it }
        private val second = if (first < 0) -1 else (first + 1 until nf).firstOrNull { input.registered[it] } ?: -1
        private var dist = 0.0
        private val sphere: Boolean
        private val tb = DoubleArray(6) // two tangent vectors of the sphere at the second camera's centre

        // Reduced camera system layout: dim 0 (fixed or unregistered), 5 (on the sphere) or 6, at offset off.
        private val dim = IntArray(nf)
        private val off = IntArray(nf)
        private val n: Int

        // Observations, compacted in place when dropped.
        private var no = no0
        private val obsF = input.obsFrame.copyOf()
        private val obsP = input.obsPoint.copyOf()
        private val uv = DoubleArray(2 * no0) { input.obsUv[it].toDouble() }
        private val alive = BooleanArray(np) { true }
        private val ptStart = IntArray(np + 1)
        private val ptObs = IntArray(no0)
        private val fill = IntArray(np)
        private var maxPerPoint = 0

        // Linearisation, per observation / camera / point.
        private val res = DoubleArray(2 * no0)
        private val wgt = DoubleArray(no0)
        private val jc = DoubleArray(12 * no0) // 2 x 6 rows (only dim columns used)
        private val jp = DoubleArray(6 * no0) // 2 x 3
        private val wo = DoubleArray(18 * no0) // Jc^T w Jp, 6 x 3
        private val u = DoubleArray(36 * nf) // Jc^T w Jc, 6 x 6
        private val gc = DoubleArray(6 * nf)
        private val v = DoubleArray(9 * np) // Jp^T w Jp
        private val gp = DoubleArray(3 * np)

        // Per trial.
        private val s: DoubleArray
        private val rhs: DoubleArray
        private val vInv = DoubleArray(9 * np)
        private val vg = DoubleArray(3 * np)
        private var y = DoubleArray(0) // Y = W V^-1 per observation of one point, 18 each
        private val dp = DoubleArray(3 * np)
        private val tmp = DoubleArray(9)

        var cancelled = false
            private set
        private var lambda = LAMBDA_START

        init {
            for (f in 0 until nf) {
                if (!input.registered[f]) continue
                quatToMat(input.rotationsCcvW, 4 * f, rot, 9 * f)
                val b = 9 * f
                val t0 = input.translationsCcvW[3 * f].toDouble()
                val t1 = input.translationsCcvW[3 * f + 1].toDouble()
                val t2 = input.translationsCcvW[3 * f + 2].toDouble()
                for (i in 0..2) cen[3 * f + i] = -(rot[b + i] * t0 + rot[b + 3 + i] * t1 + rot[b + 6 + i] * t2)
            }
            if (second >= 0) {
                var d2 = 0.0
                for (i in 0..2) d2 += (cen[3 * second + i] - cen[3 * first + i]).let { it * it }
                dist = sqrt(d2)
            }
            sphere = second >= 0 && dist > 1e-12
            var o = 0
            for (f in 0 until nf) {
                dim[f] = when {
                    !input.registered[f] || f == first -> 0
                    f == second && sphere -> 5
                    else -> 6
                }
                off[f] = o
                o += dim[f]
            }
            n = o
            s = DoubleArray(n * n)
            rhs = DoubleArray(n)
            rebuildPointIndex()
        }

        private fun rebuildPointIndex() {
            ptStart.fill(0)
            for (o in 0 until no) ptStart[obsP[o] + 1]++
            maxPerPoint = 0
            for (p in 0 until np) {
                maxPerPoint = max(maxPerPoint, ptStart[p + 1])
                ptStart[p + 1] += ptStart[p]
            }
            for (p in 0 until np) fill[p] = ptStart[p]
            for (o in 0 until no) ptObs[fill[obsP[o]]++] = o
            if (y.size < 18 * maxPerPoint) y = DoubleArray(18 * maxPerPoint)
        }

        /** Huber cost of the parameters ([candidate]: the trial ones) over the live observations. */
        private fun cost(candidate: Boolean): Double {
            val r = if (candidate) rotN else rot
            val c = if (candidate) cenN else cen
            val x = if (candidate) ptsN else pts
            var sum = 0.0
            for (o in 0 until no) {
                val f = obsF[o]
                val p = obsP[o]
                val b = 9 * f
                val dx = x[3 * p] - c[3 * f]
                val dy = x[3 * p + 1] - c[3 * f + 1]
                val dz = x[3 * p + 2] - c[3 * f + 2]
                val zc = r[b + 6] * dx + r[b + 7] * dy + r[b + 8] * dz
                val e = if (zc <= MIN_Z) {
                    BEHIND_PX
                } else {
                    val xc = r[b] * dx + r[b + 1] * dy + r[b + 2] * dz
                    val yc = r[b + 3] * dx + r[b + 4] * dy + r[b + 5] * dz
                    val du = fx[f] * xc / zc + cx[f] - uv[2 * o]
                    val dv = fy[f] * yc / zc + cy[f] - uv[2 * o + 1]
                    sqrt(du * du + dv * dv)
                }
                sum += if (e <= HUBER_PX) e * e else 2 * HUBER_PX * e - HUBER_PX * HUBER_PX
            }
            return sum
        }

        /** Residuals, Huber weights, Jacobians and the normal-equation blocks at the current parameters. */
        private fun linearise() {
            u.fill(0.0); gc.fill(0.0); v.fill(0.0); gp.fill(0.0)
            if (sphere) {
                // Tangent basis at u = (C2 - C1) / dist.
                val ux = (cen[3 * second] - cen[3 * first]) / dist
                val uy = (cen[3 * second + 1] - cen[3 * first + 1]) / dist
                val uz = (cen[3 * second + 2] - cen[3 * first + 2]) / dist
                // a = the axis least aligned with u, b1 = unit(a x u), b2 = u x b1.
                var ax = 0.0; var ay = 0.0; var az = 0.0
                if (abs(ux) <= abs(uy) && abs(ux) <= abs(uz)) ax = 1.0 else if (abs(uy) <= abs(uz)) ay = 1.0 else az = 1.0
                var b1x = ay * uz - az * uy; var b1y = az * ux - ax * uz; var b1z = ax * uy - ay * ux
                val l = sqrt(b1x * b1x + b1y * b1y + b1z * b1z)
                b1x /= l; b1y /= l; b1z /= l
                tb[0] = b1x; tb[1] = b1y; tb[2] = b1z
                tb[3] = uy * b1z - uz * b1y; tb[4] = uz * b1x - ux * b1z; tb[5] = ux * b1y - uy * b1x
            }
            for (o in 0 until no) {
                val f = obsF[o]
                val p = obsP[o]
                val b = 9 * f
                val dx = pts[3 * p] - cen[3 * f]
                val dy = pts[3 * p + 1] - cen[3 * f + 1]
                val dz = pts[3 * p + 2] - cen[3 * f + 2]
                val xc = rot[b] * dx + rot[b + 1] * dy + rot[b + 2] * dz
                val yc = rot[b + 3] * dx + rot[b + 4] * dy + rot[b + 5] * dz
                val zc = rot[b + 6] * dx + rot[b + 7] * dy + rot[b + 8] * dz
                val jo = 12 * o
                val po = 6 * o
                if (zc <= MIN_Z) {
                    wgt[o] = 0.0
                    res[2 * o] = 0.0; res[2 * o + 1] = 0.0
                    for (k in 0 until 12) jc[jo + k] = 0.0
                    for (k in 0 until 6) jp[po + k] = 0.0
                    for (k in 0 until 18) wo[18 * o + k] = 0.0
                    continue
                }
                val iz = 1.0 / zc
                val ru = fx[f] * xc * iz + cx[f] - uv[2 * o]
                val rv = fy[f] * yc * iz + cy[f] - uv[2 * o + 1]
                res[2 * o] = ru; res[2 * o + 1] = rv
                val e = sqrt(ru * ru + rv * rv)
                val w = if (e <= HUBER_PX) 1.0 else HUBER_PX / e
                wgt[o] = w
                // P = d(u, v) / d x_c.
                val p00 = fx[f] * iz; val p02 = -fx[f] * xc * iz * iz
                val p11 = fy[f] * iz; val p12 = -fy[f] * yc * iz * iz
                // Jp = P R.
                for (k in 0..2) {
                    jp[po + k] = p00 * rot[b + k] + p02 * rot[b + 6 + k]
                    jp[po + 3 + k] = p11 * rot[b + 3 + k] + p12 * rot[b + 6 + k]
                }
                val d = dim[f]
                if (d > 0) {
                    // d x_c / d w = -[x_c]x (left update); d x_c / d C = -R.
                    jc[jo] = p02 * yc
                    jc[jo + 1] = p00 * zc - p02 * xc
                    jc[jo + 2] = -p00 * yc
                    jc[jo + 6] = -p11 * zc + p12 * yc
                    jc[jo + 7] = -p12 * xc
                    jc[jo + 8] = p11 * xc
                    if (d == 6) {
                        for (k in 0..2) {
                            jc[jo + 3 + k] = -jp[po + k]
                            jc[jo + 9 + k] = -jp[po + 3 + k]
                        }
                    } else {
                        // Centre on the sphere: d C / d(a, b) = dist [b1 b2].
                        for (row in 0..1) {
                            val q = po + 3 * row
                            val j1 = -(jp[q] * tb[0] + jp[q + 1] * tb[1] + jp[q + 2] * tb[2]) * dist
                            val j2 = -(jp[q] * tb[3] + jp[q + 1] * tb[4] + jp[q + 2] * tb[5]) * dist
                            jc[jo + 6 * row + 3] = j1
                            jc[jo + 6 * row + 4] = j2
                            jc[jo + 6 * row + 5] = 0.0
                        }
                    }
                    val ub = 36 * f
                    for (a in 0 until d) {
                        val ja0 = jc[jo + a]
                        val ja1 = jc[jo + 6 + a]
                        gc[6 * f + a] += w * (ja0 * ru + ja1 * rv)
                        for (c in 0..a) u[ub + 6 * a + c] += w * (ja0 * jc[jo + c] + ja1 * jc[jo + 6 + c])
                        for (c in 0..2) wo[18 * o + 3 * a + c] = w * (ja0 * jp[po + c] + ja1 * jp[po + 3 + c])
                    }
                }
                val vb = 9 * p
                for (a in 0..2) {
                    val ja0 = jp[po + a]
                    val ja1 = jp[po + 3 + a]
                    gp[3 * p + a] += w * (ja0 * ru + ja1 * rv)
                    for (c in 0..a) v[vb + 3 * a + c] += w * (ja0 * jp[po + c] + ja1 * jp[po + 3 + c])
                }
            }
            // Mirror the lower triangles.
            for (f in 0 until nf) {
                val ub = 36 * f
                for (a in 0 until 6) for (c in a + 1 until 6) u[ub + 6 * a + c] = u[ub + 6 * c + a]
            }
            for (p in 0 until np) {
                val vb = 9 * p
                v[vb + 1] = v[vb + 3]; v[vb + 2] = v[vb + 6]; v[vb + 5] = v[vb + 7]
            }
        }

        private fun damp(x: Double) = lambda * min(max(x, 1e-6), 1e32)

        /** Solves the damped system for the step and writes the candidate parameters; false when it is singular. */
        private fun step(): Boolean {
            fillCameraBlocks()
            // Eliminate the points: S -= W V^-1 W^T, rhs += W V^-1 gp.
            for (p in 0 until np) if (!eliminatePoint(p)) return false
            if (!cholesky()) return false
            // rhs now holds the camera step; back-substitute the points: dp = V^-1 (-gp - W^T dc).
            for (p in 0 until np) backSubstitute(p)
            applyStep()
            return true
        }

        /** S and rhs from the camera blocks of U and gc, with the damping on S's diagonal. */
        private fun fillCameraBlocks() {
            s.fill(0.0)
            for (f in 0 until nf) {
                val d = dim[f]
                if (d == 0) continue
                val o = off[f]
                val ub = 36 * f
                for (a in 0 until d) {
                    rhs[o + a] = -gc[6 * f + a]
                    for (c in 0..a) s[(o + a) * n + o + c] = u[ub + 6 * a + c]
                    s[(o + a) * n + o + a] += damp(u[ub + 7 * a])
                }
            }
        }

        /** Point [p] eliminated from S and rhs (its damped V inverted into vInv); false when V is singular. */
        private fun eliminatePoint(p: Int): Boolean {
            val start = ptStart[p]
            val end = ptStart[p + 1]
            if (!alive[p] || start == end) return true
            val vb = 9 * p
            for (k in 0 until 9) tmp[k] = v[vb + k]
            tmp[0] += damp(v[vb]); tmp[4] += damp(v[vb + 4]); tmp[8] += damp(v[vb + 8])
            if (!invert3(tmp, vInv, vb)) return false
            for (a in 0..2) {
                vg[3 * p + a] = vInv[vb + 3 * a] * gp[3 * p] + vInv[vb + 3 * a + 1] * gp[3 * p + 1] +
                    vInv[vb + 3 * a + 2] * gp[3 * p + 2]
            }
            for (i in start until end) {
                val o = ptObs[i]
                val d = dim[obsF[o]]
                if (d == 0 || wgt[o] == 0.0) continue
                val yb = 18 * (i - start)
                val wb = 18 * o
                val ro = off[obsF[o]]
                for (a in 0 until d) {
                    for (c in 0..2) {
                        y[yb + 3 * a + c] = wo[wb + 3 * a] * vInv[vb + c] + wo[wb + 3 * a + 1] * vInv[vb + 3 + c] +
                            wo[wb + 3 * a + 2] * vInv[vb + 6 + c]
                    }
                    rhs[ro + a] += wo[wb + 3 * a] * vg[3 * p] + wo[wb + 3 * a + 1] * vg[3 * p + 1] +
                        wo[wb + 3 * a + 2] * vg[3 * p + 2]
                }
            }
            for (i in start until end) schurUpdate(i, start, end)
            return true
        }

        /** S -= Y_i W_j^T for observation [i] of a point and every observation j in [start, end) left of it in S. */
        private fun schurUpdate(i: Int, start: Int, end: Int) {
            val oi = ptObs[i]
            val fi = obsF[oi]
            val di = dim[fi]
            if (di == 0 || wgt[oi] == 0.0) return
            val yb = 18 * (i - start)
            val ri = off[fi]
            for (j in start until end) {
                val oj = ptObs[j]
                val fj = obsF[oj]
                val dj = dim[fj]
                if (dj == 0 || wgt[oj] == 0.0 || off[fj] > ri) continue
                val wb = 18 * oj
                val rj = off[fj]
                for (a in 0 until di) {
                    val row = (ri + a) * n + rj
                    val y0 = y[yb + 3 * a]; val y1 = y[yb + 3 * a + 1]; val y2 = y[yb + 3 * a + 2]
                    for (c in 0 until dj) {
                        s[row + c] -= y0 * wo[wb + 3 * c] + y1 * wo[wb + 3 * c + 1] + y2 * wo[wb + 3 * c + 2]
                    }
                }
            }
        }

        /** dp of point [p] from the solved camera step in rhs: V^-1 (-gp - W^T dc); 0 for a dead or unseen point. */
        private fun backSubstitute(p: Int) {
            val start = ptStart[p]
            val end = ptStart[p + 1]
            if (!alive[p] || start == end) {
                dp[3 * p] = 0.0; dp[3 * p + 1] = 0.0; dp[3 * p + 2] = 0.0
                return
            }
            var b0 = -gp[3 * p]; var b1 = -gp[3 * p + 1]; var b2 = -gp[3 * p + 2]
            for (i in start until end) {
                val o = ptObs[i]
                val d = dim[obsF[o]]
                if (d == 0 || wgt[o] == 0.0) continue
                val ro = off[obsF[o]]
                val wb = 18 * o
                for (a in 0 until d) {
                    val dc = rhs[ro + a]
                    b0 -= wo[wb + 3 * a] * dc; b1 -= wo[wb + 3 * a + 1] * dc; b2 -= wo[wb + 3 * a + 2] * dc
                }
            }
            val vb = 9 * p
            for (a in 0..2) dp[3 * p + a] = vInv[vb + 3 * a] * b0 + vInv[vb + 3 * a + 1] * b1 + vInv[vb + 3 * a + 2] * b2
        }

        private fun applyStep() {
            rot.copyInto(rotN)
            cen.copyInto(cenN)
            for (i in 0 until 3 * np) ptsN[i] = pts[i] + dp[i]
            for (f in 0 until nf) {
                val d = dim[f]
                if (d == 0) continue
                val o = off[f]
                leftRotate(rhs[o], rhs[o + 1], rhs[o + 2], rot, rotN, 9 * f)
                if (d == 6) {
                    for (k in 0..2) cenN[3 * f + k] = cen[3 * f + k] + rhs[o + 3 + k]
                } else {
                    val a = rhs[o + 3]
                    val b = rhs[o + 4]
                    var ux = (cen[3 * f] - cen[3 * first]) / dist + a * tb[0] + b * tb[3]
                    var uy = (cen[3 * f + 1] - cen[3 * first + 1]) / dist + a * tb[1] + b * tb[4]
                    var uz = (cen[3 * f + 2] - cen[3 * first + 2]) / dist + a * tb[2] + b * tb[5]
                    val l = sqrt(ux * ux + uy * uy + uz * uz)
                    ux /= l; uy /= l; uz /= l
                    cenN[3 * f] = cen[3 * first] + dist * ux
                    cenN[3 * f + 1] = cen[3 * first + 1] + dist * uy
                    cenN[3 * f + 2] = cen[3 * first + 2] + dist * uz
                }
            }
        }

        /** dst[at] = exp([w]x) src[at] (Rodrigues), row-major 3 x 3. */
        private fun leftRotate(wx: Double, wy: Double, wz: Double, src: DoubleArray, dst: DoubleArray, at: Int) {
            val th = sqrt(wx * wx + wy * wy + wz * wz)
            val a: Double
            val b: Double
            if (th < 1e-8) {
                a = 1.0 - th * th / 6
                b = 0.5 - th * th / 24
            } else {
                a = sin(th) / th
                b = (1 - cos(th)) / (th * th)
            }
            // E = I + a K + b K^2, K = [w]x.
            val e00 = 1 - b * (wy * wy + wz * wz)
            val e11 = 1 - b * (wx * wx + wz * wz)
            val e22 = 1 - b * (wx * wx + wy * wy)
            val e01 = -a * wz + b * wx * wy
            val e10 = a * wz + b * wx * wy
            val e02 = a * wy + b * wx * wz
            val e20 = -a * wy + b * wx * wz
            val e12 = -a * wx + b * wy * wz
            val e21 = a * wx + b * wy * wz
            for (c in 0..2) {
                val s0 = src[at + c]; val s1 = src[at + 3 + c]; val s2 = src[at + 6 + c]
                dst[at + c] = e00 * s0 + e01 * s1 + e02 * s2
                dst[at + 3 + c] = e10 * s0 + e11 * s1 + e12 * s2
                dst[at + 6 + c] = e20 * s0 + e21 * s1 + e22 * s2
            }
        }

        /** Symmetric 3 x 3 [m] inverted into [out] at [at]; false when not positive definite. */
        private fun invert3(m: DoubleArray, out: DoubleArray, at: Int): Boolean {
            val c00 = m[4] * m[8] - m[5] * m[7]
            val c01 = m[5] * m[6] - m[3] * m[8]
            val c02 = m[3] * m[7] - m[4] * m[6]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            if (!(det > 0) || !(m[0] > 0)) return false
            val id = 1.0 / det
            out[at] = c00 * id
            out[at + 1] = (m[2] * m[7] - m[1] * m[8]) * id
            out[at + 2] = (m[1] * m[5] - m[2] * m[4]) * id
            out[at + 3] = c01 * id
            out[at + 4] = (m[0] * m[8] - m[2] * m[6]) * id
            out[at + 5] = (m[2] * m[3] - m[0] * m[5]) * id
            out[at + 6] = c02 * id
            out[at + 7] = (m[1] * m[6] - m[0] * m[7]) * id
            out[at + 8] = (m[0] * m[4] - m[1] * m[3]) * id
            return true
        }

        /** In-place dense Cholesky of the lower triangle of [s], then solves for [rhs] in place. */
        private fun cholesky(): Boolean {
            for (j in 0 until n) {
                val rj = j * n
                var d = s[rj + j]
                for (k in 0 until j) d -= s[rj + k] * s[rj + k]
                if (!(d > 0)) return false
                val l = sqrt(d)
                s[rj + j] = l
                val il = 1.0 / l
                for (i in j + 1 until n) {
                    val ri = i * n
                    var x = s[ri + j]
                    for (k in 0 until j) x -= s[ri + k] * s[rj + k]
                    s[ri + j] = x * il
                }
            }
            for (i in 0 until n) {
                val ri = i * n
                var x = rhs[i]
                for (k in 0 until i) x -= s[ri + k] * rhs[k]
                rhs[i] = x / s[ri + i]
            }
            for (i in n - 1 downTo 0) {
                var x = rhs[i]
                for (k in i + 1 until n) x -= s[k * n + i] * rhs[k]
                rhs[i] = x / s[i * n + i]
            }
            return true
        }

        /** Up to [budget] LM iterations; returns the iterations run. */
        fun optimise(budget: Int, isCancelled: () -> Boolean): Int {
            if (no == 0) return 0
            var it = 0
            var current = cost(false)
            var converged = false
            while (it < budget) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                if (converged) break
                it++
                linearise()
                var accepted = false
                var trial = 0
                while (trial < MAX_TRIALS) {
                    trial++
                    if (step()) {
                        val next = cost(true)
                        if (next < current) {
                            swap()
                            val change = (current - next) / max(current, 1e-300)
                            current = next
                            lambda = max(lambda / 3, 1e-12)
                            accepted = true
                            if (change < REL_COST_TOL) converged = true
                            break
                        }
                    }
                    lambda = min(lambda * 10, 1e16)
                }
                if (!accepted) converged = true
            }
            return it
        }

        private fun swap() {
            val r = rot; rot = rotN; rotN = r
            val c = cen; cen = cenN; cenN = c
            val x = pts; pts = ptsN; ptsN = x
        }

        /** Drops observations over [DROP_PX] and then points under 2 observations; returns how many observations went. */
        fun dropOutliers(): Int {
            val start = no
            var k = 0
            for (o in 0 until no) {
                if (error(o) <= DROP_PX) keep(o, k++)
            }
            no = k
            // Points left with fewer than 2 observations go, with their observation.
            val count = fill
            count.fill(0)
            for (o in 0 until no) count[obsP[o]]++
            for (p in 0 until np) if (count[p] < 2) alive[p] = false
            k = 0
            for (o in 0 until no) if (alive[obsP[o]]) keep(o, k++)
            no = k
            rebuildPointIndex()
            return start - no
        }

        private fun keep(from: Int, to: Int) {
            obsF[to] = obsF[from]
            obsP[to] = obsP[from]
            uv[2 * to] = uv[2 * from]
            uv[2 * to + 1] = uv[2 * from + 1]
        }

        private fun error(o: Int): Double {
            val f = obsF[o]
            val p = obsP[o]
            val b = 9 * f
            val dx = pts[3 * p] - cen[3 * f]
            val dy = pts[3 * p + 1] - cen[3 * f + 1]
            val dz = pts[3 * p + 2] - cen[3 * f + 2]
            val zc = rot[b + 6] * dx + rot[b + 7] * dy + rot[b + 8] * dz
            if (zc <= MIN_Z) return Double.POSITIVE_INFINITY
            val xc = rot[b] * dx + rot[b + 1] * dy + rot[b + 2] * dz
            val yc = rot[b + 3] * dx + rot[b + 4] * dy + rot[b + 5] * dz
            val du = fx[f] * xc / zc + cx[f] - uv[2 * o]
            val dv = fy[f] * yc / zc + cy[f] - uv[2 * o + 1]
            return sqrt(du * du + dv * dv)
        }

        /** The adjusted model: live points re-indexed in order, kept observations; first and unregistered slots copied. */
        fun toModel(): SparseModel {
            val rotations = input.rotationsCcvW.copyOf()
            val translations = input.translationsCcvW.copyOf()
            for (f in 0 until nf) {
                if (dim[f] == 0) continue
                matToQuat(rot, 9 * f, rotations, 4 * f)
                val b = 9 * f
                for (i in 0..2) {
                    translations[3 * f + i] = (-(rot[b + 3 * i] * cen[3 * f] + rot[b + 3 * i + 1] * cen[3 * f + 1] +
                        rot[b + 3 * i + 2] * cen[3 * f + 2])).toFloat()
                }
            }
            val index = IntArray(np) { -1 }
            var m = 0
            for (p in 0 until np) if (alive[p]) index[p] = m++
            val points = FloatArray(3 * m)
            val colours = ByteArray(3 * m)
            for (p in 0 until np) {
                val q = index[p]
                if (q < 0) continue
                for (k in 0..2) {
                    points[3 * q + k] = pts[3 * p + k].toFloat()
                    colours[3 * q + k] = input.colours[3 * p + k]
                }
            }
            val obsPoint = IntArray(no) { index[obsP[it]] }
            return SparseModel(
                input.registered.copyOf(), rotations, translations, points, colours,
                obsF.copyOf(no), obsPoint, FloatArray(2 * no) { uv[it].toFloat() },
            )
        }
    }
}
