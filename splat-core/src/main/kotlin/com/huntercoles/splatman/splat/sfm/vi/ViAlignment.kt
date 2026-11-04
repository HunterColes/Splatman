package com.huntercoles.splatman.splat.sfm.vi

import com.huntercoles.splatman.splat.sfm.vi.Mat3.add
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mul
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mv
import com.huntercoles.splatman.splat.sfm.vi.Mat3.scale
import com.huntercoles.splatman.splat.sfm.vi.Mat3.sub
import com.huntercoles.splatman.splat.sfm.vi.Mat3.tmul
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A keyframe's camera pose from SfM in its own frame W: `R_WC` (row-major, C into W) and the camera centre in W, in
 * SfM units (metric up to the unknown scale). W need not be gravity-aligned.
 */
class ViPose(val rWC: DoubleArray, val pWC: DoubleArray) {
    init {
        require(rWC.size == 9 && pWC.size == 3) { "rWC must be 3x3 and pWC a 3-vector" }
    }
}

/** What [ViAlignment.align] returns: a [ViInit] or a [ViAlignFailure]. */
sealed interface ViAlignResult

/**
 * The visual-inertial initialisation. [scale] is metres per SfM unit (`p_metric = scale * pWC`). [gravityG] is gravity
 * expressed in the poses' frame W (m/s^2, norm [ViAlignment.GRAVITY]): the rotation taking it to `(0, 0, -g)` makes W
 * the gravity-aligned G of ADR 0016 point 5. [velocities] are of the IMU body S at each keyframe, in W, m/s.
 * [gyroBias] and [accelBias] are in rad/s and m/s^2, S axes ([accelBias] is the first window's linearisation point
 * plus the solved offset). [conditioning] is [ViAlignment.conditioning] of the final solve.
 */
class ViInit(
    val scale: Double,
    val gravityG: DoubleArray,
    val velocities: List<DoubleArray>,
    val gyroBias: DoubleArray,
    val accelBias: DoubleArray,
    val conditioning: Double,
) : ViAlignResult

enum class ViFailureReason {
    /** Fewer than [ViAlignment.MIN_KEYFRAMES] keyframes. */
    TOO_FEW_KEYFRAMES,

    /** A preintegration window bridges a gap in the IMU data ([ImuPreintegration.valid] is false). */
    IMU_GAP,

    /** [ViAlignment.conditioning] under [ViAlignment.MIN_CONDITIONING]: scale and gravity are not observable. */
    TOO_LITTLE_MOTION,

    /** The solve put the scale at or below zero. */
    NON_POSITIVE_SCALE,
}

/** Why [ViAlignment.align] gave up; [conditioning] is NaN when it stopped before the linear solve. */
class ViAlignFailure(
    val reason: ViFailureReason,
    val message: String,
    val conditioning: Double = Double.NaN,
) : ViAlignResult {
    override fun toString() = "ViAlignFailure($reason: $message)"
}

/**
 * Visual-inertial alignment (Qin, Li, Shen, VINS-Mono, T-RO 2018, section V-B), written from the paper: from SfM
 * keyframe poses known up to scale and the IMU preintegrated between consecutive keyframes, recover the gyro bias,
 * the metric scale, the velocities and gravity.
 *
 * 1. Gyro bias: Gauss-Newton on `sum_k |Log(dR_k(bg)^T R_WS_k^T R_WS_k+1)|^2`, `R_WS = R_WC R_CS`, with the
 *    first-order bias update of [ImuPreintegration.corrected].
 * 2. Linear solve for `x = [v_0 .. v_n-1, g, s]`, velocities and gravity in W, per window (dt, deltas `dP`, `dV` with
 *    the new gyro bias and the preintegration's own accel bias):
 *    `s (pc_j - pc_i) - v_i dt - g dt^2 / 2 = R_i dP + (R_j - R_i) l` and `v_j - v_i - g dt = R_i dV`, where `pc` are
 *    the SfM camera centres and `l` the lever arm (the camera centre in S); body position `p = s pc - R_WS l`.
 *    Both are solved divided by `s`, in `[v / s, g / s, lambda = 1 / s]`: the SfM noise then sits in the observation
 *    `pc_j - pc_i` instead of in a regressor, where it would pull the scale towards 0 (errors in variables: at 1 mm
 *    SfM noise and 0.1 s windows the scale came out 25 % low).
 * 3. Gravity on S2: `g = |g| g^ + B w` with `B` a basis of the tangent at `g^`; the same system in `[v, w, s, dba]`
 *    (divided by `s` as in step 2),
 *    [S2_ITERATIONS] times, `g^` renormalised each time with `|g|` = [GRAVITY]. Unlike VINS-Mono this step also
 *    solves the accelerometer bias offset `dba` (through `dP/dba`, `dV/dba`), as Mur-Artal and Tardos (RA-L 2017,
 *    section IV-C) do once `|g|` is fixed: a phone's 0.1 m/s^2 bias is as large as a slow orbit's accelerations and
 *    otherwise flips the scale's sign.
 *
 * Rows are whitened by the preintegration's covariance (position and velocity blocks, mean of the diagonal) plus
 * [POSITION_FLOOR] and [VELOCITY_FLOOR] for the SfM and model error the covariance does not hold. Position rows are
 * Huber-weighted per window (threshold [HUBER_K] times the median residual norm, at least [HUBER_FLOOR] m), so one
 * bad SfM pose cannot pull the scale. The scale's sign is checked only after step 3.
 */
object ViAlignment {
    const val GRAVITY = 9.81

    /** At least two windows: one alone cannot separate velocity from gravity. */
    const val MIN_KEYFRAMES = 3

    /**
     * Lower bound on [conditioning]. With Pixel-grade IMU noise and 0.5 mm / 0.03 deg SfM noise, ViSim's STATIC still
     * part (2 s) scores about 0.04 and a turn about the camera centre 0; the whole STATIC sweep about 18, ORBIT 70,
     * ROOM 240. Roughly `1 / sd(ln s)`, pessimistic by the floors.
     */
    const val MIN_CONDITIONING = 1.0

    const val HUBER_K = 3.0
    const val HUBER_FLOOR = 1e-4
    const val POSITION_FLOOR = 1e-3
    const val VELOCITY_FLOOR = 1e-3
    const val GYRO_ITERATIONS = 10
    const val IRLS_ITERATIONS = 4
    const val S2_ITERATIONS = 4

    private const val GYRO_STEP_TOL = 1e-12

    /**
     * Aligns [poses] (n keyframes, W, SfM units) with [preintegrations] (n - 1 windows, window k from keyframe k to
     * k + 1). [rCS] is the camera-IMU rotation `R_CS` (S into C, row-major), [leverArm] the camera centre in S (m).
     */
    fun align(
        poses: List<ViPose>,
        preintegrations: List<ImuPreintegration>,
        rCS: DoubleArray,
        leverArm: DoubleArray,
    ): ViAlignResult {
        require(preintegrations.size == poses.size - 1 || poses.isEmpty()) {
            "need one preintegration per consecutive keyframe pair: ${poses.size} poses, ${preintegrations.size} windows"
        }
        require(rCS.size == 9 && leverArm.size == 3) { "rCS must be 3x3 and leverArm a 3-vector" }
        if (poses.size < MIN_KEYFRAMES) {
            return ViAlignFailure(ViFailureReason.TOO_FEW_KEYFRAMES, "${poses.size} keyframes, need $MIN_KEYFRAMES")
        }
        val gap = preintegrations.indexOfFirst { !it.valid }
        if (gap >= 0) return ViAlignFailure(ViFailureReason.IMU_GAP, "window $gap bridges an IMU gap")

        val rWS = poses.map { mul(it.rWC, rCS) }
        val bg = gyroBias(rWS, preintegrations)
        val windows = preintegrations.mapIndexed { k, pre -> Window.of(k, pre, bg, poses, rWS, leverArm) }
        val n = poses.size
        val weights = DoubleArray(windows.size) { 1.0 }

        // Step 2: g free.
        var free: ArrowSolution? = null
        repeat(IRLS_ITERATIONS) {
            val sol = solve(n, windows, weights, FreeGravity) ?: return tooLittle(0.0)
            reweight(windows, sol, FreeGravity, weights)
            free = sol
        }
        // g' = g / s: the free scale's sign gives gravity's.
        val lambda0 = free!!.x[FreeGravity.lambdaIndex]
        val g0 = scale(free!!.x.copyOfRange(0, 3), if (lambda0 < 0) -1.0 else 1.0)
        if (!(Mat3.norm(g0) > 0.0)) return tooLittle(0.0)

        // Step 3: gravity on S2 and the accel bias, from the free estimate's direction.
        var dir = scale(g0, 1.0 / Mat3.norm(g0))
        var last: ArrowSolution = free!!
        repeat(S2_ITERATIONS) {
            val model = SphereGravity(dir)
            last = solve(n, windows, weights, model) ?: return tooLittle(0.0)
            reweight(windows, last, model, weights)
            val lambda = last.x[SphereGravity.LAMBDA]
            if (!(lambda > 0.0)) return@repeat
            val g = add(scale(dir, GRAVITY), scale(mv3x2(model.basis, last.x), 1.0 / lambda))
            if (!(Mat3.norm(g) > 0.0)) return tooLittle(0.0)
            dir = scale(g, 1.0 / Mat3.norm(g))
        }
        val lambda = last.x[SphereGravity.LAMBDA]
        val cond = conditioning(last.schur)
        if (!(cond >= MIN_CONDITIONING)) return tooLittle(cond)
        if (!(lambda > 0.0)) return ViAlignFailure(ViFailureReason.NON_POSITIVE_SCALE, "scale ${1 / lambda} <= 0", cond)
        return ViInit(
            scale = 1 / lambda,
            gravityG = scale(dir, GRAVITY),
            velocities = List(n) { scale(last.v.copyOfRange(3 * it, 3 * it + 3), 1 / lambda) },
            gyroBias = bg,
            accelBias = add(preintegrations[0].accelBias, scale(last.x.copyOfRange(3, 6), 1 / lambda)),
            conditioning = cond,
        )
    }

    /**
     * The smallest singular value of the whitened step-3 system after the velocities are eliminated: the square root
     * of the smallest eigenvalue of the Schur complement on its border. At the solution the border's columns are
     * those of gravity tilt (m/s^2), `ln s` and accel bias (m/s^2) in the metric system, so it is dimensionless: the
     * inverse standard deviation of their worst-determined combination.
     */
    internal fun conditioning(schur: Array<DoubleArray>): Double =
        sqrt(max(ViDense.symmetricEigenvalues(schur).min(), 0.0))

    /** Gauss-Newton for the gyro bias from the SfM rotations, starting at the first window's linearisation point. */
    internal fun gyroBias(rWS: List<DoubleArray>, pres: List<ImuPreintegration>): DoubleArray {
        var bg = pres[0].gyroBias.copyOf()
        repeat(GYRO_ITERATIONS) {
            val h = Array(3) { DoubleArray(3) }
            val b = DoubleArray(3)
            for ((k, pre) in pres.withIndex()) {
                val e = tmul(pre.corrected(bg, pre.accelBias).deltaR, tmul(rWS[k], rWS[k + 1]))
                val r = SO3.log(e)
                val jb = mul(SO3.rightJacobian(mv(pre.dRdBg, sub(bg, pre.gyroBias))), pre.dRdBg)
                val j = scale(mul(SO3.rightJacobianInverse(r), mul(Mat3.transpose(e), jb)), -1.0)
                for (p in 0..2) {
                    for (q in 0..2) for (t in 0..2) h[p][q] += j[3 * t + p] * j[3 * t + q]
                    for (t in 0..2) b[p] -= j[3 * t + p] * r[t]
                }
            }
            val dx = ViDense.solveSpd(h, b) ?: return bg
            bg = add(bg, dx)
            if (Mat3.norm(dx) < GYRO_STEP_TOL) return bg
        }
        return bg
    }

    private fun tooLittle(cond: Double) = ViAlignFailure(
        ViFailureReason.TOO_LITTLE_MOTION,
        "too little motion: conditioning $cond under $MIN_CONDITIONING",
        cond,
    )

    private fun mv3x2(b: DoubleArray, w: DoubleArray) = DoubleArray(3) { b[2 * it] * w[0] + b[2 * it + 1] * w[1] }

    /**
     * One window's fixed data: SfM displacement, the right-hand sides of its position and velocity rows, their
     * accel-bias Jacobians in W (`R_i dP/dba`, `R_i dV/dba`) and their standard deviations.
     */
    private class Window(
        val i: Int,
        val dt: Double,
        val dp: DoubleArray,
        val rhsP: DoubleArray,
        val rhsV: DoubleArray,
        val baP: DoubleArray,
        val baV: DoubleArray,
        val sigmaP: Double,
        val sigmaV: Double,
    ) {
        companion object {
            fun of(
                k: Int,
                pre: ImuPreintegration,
                bg: DoubleArray,
                poses: List<ViPose>,
                rWS: List<DoubleArray>,
                lever: DoubleArray,
            ): Window {
                val d = pre.corrected(bg, pre.accelBias)
                val ri = rWS[k]
                val cov = pre.covariance
                val varV = (cov[3][3] + cov[4][4] + cov[5][5]) / 3
                val varP = (cov[6][6] + cov[7][7] + cov[8][8]) / 3
                return Window(
                    i = k,
                    dt = pre.dt,
                    dp = sub(poses[k + 1].pWC, poses[k].pWC),
                    rhsP = add(mv(ri, d.deltaP), mv(sub(rWS[k + 1], ri), lever)),
                    rhsV = mv(ri, d.deltaV),
                    baP = mul(ri, pre.dPdBa),
                    baV = mul(ri, pre.dVdBa),
                    sigmaP = sqrt(varP + POSITION_FLOOR * POSITION_FLOOR),
                    sigmaV = sqrt(varV + VELOCITY_FLOOR * VELOCITY_FLOOR),
                )
            }
        }
    }

    /**
     * How gravity enters a row of the system divided by the scale: the border columns and the right-hand side. A
     * position row is `dt v'_i + ... = dp`, a velocity row `v'_j - v'_i + ... = 0`.
     */
    private interface GravityModel {
        val size: Int

        /** Index of `lambda = 1 / s` in the border. */
        val lambdaIndex: Int

        /** Fills [border] for window [w]'s row on [axis] (position row or velocity row), returns its right side. */
        fun row(w: Window, axis: Int, position: Boolean, border: DoubleArray): Double
    }

    /** `[g'_x, g'_y, g'_z, lambda]`, `g' = g / s`. */
    private object FreeGravity : GravityModel {
        override val size = 4
        override val lambdaIndex = 3
        override fun row(w: Window, axis: Int, position: Boolean, border: DoubleArray): Double {
            border.fill(0.0)
            border[axis] = if (position) w.dt * w.dt / 2 else -w.dt
            border[3] = if (position) w.rhsP[axis] else -w.rhsV[axis]
            return if (position) w.dp[axis] else 0.0
        }
    }

    /** `[w'_1, w'_2, lambda, ba'_x, ba'_y, ba'_z]`: `g / s = lambda GRAVITY dir + B w'`, `ba' = dba / s`. */
    private class SphereGravity(val dir: DoubleArray) : GravityModel {
        override val size = 6
        override val lambdaIndex = LAMBDA

        /** 3x2 row-major tangent basis at [dir]. */
        val basis: DoubleArray = run {
            val helper = if (abs(dir[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val b1 = cross(dir, helper).let { scale(it, 1.0 / Mat3.norm(it)) }
            val b2 = cross(dir, b1)
            doubleArrayOf(b1[0], b2[0], b1[1], b2[1], b1[2], b2[2])
        }

        override fun row(w: Window, axis: Int, position: Boolean, border: DoubleArray): Double {
            val f = if (position) w.dt * w.dt / 2 else -w.dt
            val sign = if (position) 1.0 else -1.0
            val ba = if (position) w.baP else w.baV
            val rhs = if (position) w.rhsP[axis] else w.rhsV[axis]
            border[0] = f * basis[2 * axis]
            border[1] = f * basis[2 * axis + 1]
            border[LAMBDA] = sign * rhs + f * GRAVITY * dir[axis]
            for (c in 0..2) border[3 + c] = sign * ba[3 * axis + c]
            return if (position) w.dp[axis] else 0.0
        }

        companion object {
            const val LAMBDA = 2
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Every window's rows on every axis, whitened: position row `dt v'_i`, velocity row `v'_j - v'_i`. */
    private fun solve(n: Int, windows: List<Window>, weights: DoubleArray, model: GravityModel): ArrowSolution? {
        val sys = ArrowSystem(3 * n, model.size)
        val border = DoubleArray(model.size)
        for ((k, w) in windows.withIndex()) {
            val wp = weights[k] / (w.sigmaP * w.sigmaP)
            val wv = 1.0 / (w.sigmaV * w.sigmaV)
            for (a in 0..2) {
                val vi = 3 * w.i + a
                val rhsP = model.row(w, a, true, border)
                sys.addRow(intArrayOf(vi), doubleArrayOf(w.dt), border, rhsP, wp)
                val rhsV = model.row(w, a, false, border)
                sys.addRow(intArrayOf(vi, vi + 3), doubleArrayOf(-1.0, 1.0), border, rhsV, wv)
            }
        }
        return sys.solve()
    }

    /**
     * Huber weights of the windows' position rows from their residual norms at [sol], in metres (the rows are in
     * SfM units: times the scale). Left alone while the scale is not positive.
     */
    private fun reweight(windows: List<Window>, sol: ArrowSolution, model: GravityModel, weights: DoubleArray) {
        val lambda = sol.x[model.lambdaIndex]
        if (!(lambda > 0.0)) return
        val border = DoubleArray(model.size)
        val norms = DoubleArray(windows.size) { k ->
            val w = windows[k]
            var sq = 0.0
            for (a in 0..2) {
                val rhs = model.row(w, a, true, border)
                var r = w.dt * sol.v[3 * w.i + a] - rhs
                for (c in border.indices) r += border[c] * sol.x[c]
                sq += r * r
            }
            sqrt(sq) / lambda
        }
        val delta = max(HUBER_K * norms.sorted()[norms.size / 2], HUBER_FLOOR)
        for (k in norms.indices) weights[k] = if (norms[k] <= delta) 1.0 else delta / norms[k]
    }
}

/** Velocities [v] (3n) and border unknowns [x]; [schur] is the border's information after eliminating [v]. */
internal class ArrowSolution(val v: DoubleArray, val x: DoubleArray, val schur: Array<DoubleArray>)

/**
 * Normal equations of a least-squares problem in `nv` band-coupled unknowns (each row touches unknowns at most
 * [BW] apart) and `nb` dense border unknowns, solved by banded Cholesky and the border's Schur complement.
 */
internal class ArrowSystem(private val nv: Int, private val nb: Int) {
    private val band = Array(nv) { DoubleArray(BW + 1) } // band[i][d] = H(i, i - d)
    private val hvb = Array(nv) { DoubleArray(nb) }
    private val hbb = Array(nb) { DoubleArray(nb) }
    private val bv = DoubleArray(nv)
    private val bb = DoubleArray(nb)

    /** Adds `w (vc . v[vi] + bc . x - rhs)^2`; the indices in [vi] are distinct and at most [BW] apart. */
    fun addRow(vi: IntArray, vc: DoubleArray, bc: DoubleArray, rhs: Double, w: Double) {
        for (p in vi.indices) {
            for (q in vi.indices) if (vi[p] >= vi[q]) band[vi[p]][vi[p] - vi[q]] += w * vc[p] * vc[q]
            for (c in 0 until nb) hvb[vi[p]][c] += w * vc[p] * bc[c]
            bv[vi[p]] += w * vc[p] * rhs
        }
        for (c in 0 until nb) {
            for (d in 0 until nb) hbb[c][d] += w * bc[c] * bc[d]
            bb[c] += w * bc[c] * rhs
        }
    }

    /** The least-squares solution, or null when the band or the Schur complement is not positive definite. */
    fun solve(): ArrowSolution? {
        val l = cholesky() ?: return null
        val yb = substitute(l, bv)
        val yc = Array(nb) { c -> substitute(l, DoubleArray(nv) { hvb[it][c] }) }
        val schur = Array(nb) { c -> DoubleArray(nb) { d -> hbb[c][d] - (0 until nv).sumOf { hvb[it][c] * yc[d][it] } } }
        val rhs = DoubleArray(nb) { c -> bb[c] - (0 until nv).sumOf { hvb[it][c] * yb[it] } }
        val x = ViDense.solveSpd(schur, rhs) ?: return null
        val v = DoubleArray(nv) { i -> yb[i] - (0 until nb).sumOf { yc[it][i] * x[it] } }
        return ArrowSolution(v, x, schur)
    }

    private fun cholesky(): Array<DoubleArray>? {
        val l = Array(nv) { DoubleArray(BW + 1) }
        for (i in 0 until nv) {
            for (j in max(0, i - BW)..i) {
                var s = band[i][i - j]
                for (k in max(0, i - BW) until j) s -= l[i][i - k] * l[j][j - k]
                if (i == j) {
                    if (s <= 0.0 || !s.isFinite()) return null
                    l[i][0] = sqrt(s)
                } else {
                    l[i][i - j] = s / l[j][0]
                }
            }
        }
        return l
    }

    /** `H_vv^-1 b` from the band factor. */
    private fun substitute(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val y = DoubleArray(nv)
        for (i in 0 until nv) {
            var s = b[i]
            for (k in max(0, i - BW) until i) s -= l[i][i - k] * y[k]
            y[i] = s / l[i][0]
        }
        val x = DoubleArray(nv)
        for (i in nv - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1..min(nv - 1, i + BW)) s -= l[k][k - i] * x[k]
            x[i] = s / l[i][0]
        }
        return x
    }

    companion object {
        /** A velocity row couples `v_i` and `v_i+1` on one axis: indices 3 apart. */
        const val BW = 3
    }
}

/** Small dense symmetric helpers for the VIO solves. */
internal object ViDense {
    private const val JACOBI_SWEEPS = 60
    private const val JACOBI_TOL = 1e-30

    /** `a x = b` for symmetric positive definite [a] by Cholesky; null when it is not positive definite. */
    fun solveSpd(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 0.0 || !s.isFinite()) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) y[i] = (b[i] - (0 until i).sumOf { l[i][it] * y[it] }) / l[i][i]
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) x[i] = (y[i] - (i + 1 until n).sumOf { l[it][i] * x[it] }) / l[i][i]
        return x
    }

    /** Inverse of symmetric positive definite [a], symmetrised; null when it is not positive definite. */
    fun inverseSpd(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val cols = Array(n) { c -> solveSpd(a, DoubleArray(n) { if (it == c) 1.0 else 0.0 }) ?: return null }
        return Array(n) { i -> DoubleArray(n) { j -> (cols[j][i] + cols[i][j]) / 2 } }
    }

    /** Eigenvalues of symmetric [a] by cyclic Jacobi rotations. */
    fun symmetricEigenvalues(a: Array<DoubleArray>): DoubleArray = symmetricEigen(a).first

    /** Eigenvalues and eigenvectors (`vectors[row][k]` is eigenvector k's entry) of symmetric [a], cyclic Jacobi. */
    fun symmetricEigen(a: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = a.size
        val m = Array(n) { a[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        repeat(JACOBI_SWEEPS) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += m[p][q] * m[p][q]
            if (off < JACOBI_TOL) return Pair(DoubleArray(n) { m[it][it] }, v)
            for (p in 0 until n) for (q in p + 1 until n) if (m[p][q] != 0.0) rotate(m, v, p, q)
        }
        return Pair(DoubleArray(n) { m[it][it] }, v)
    }

    /** One Jacobi rotation zeroing `m[p][q]`: `m <- J^T m J`, `v <- v J`. */
    private fun rotate(m: Array<DoubleArray>, v: Array<DoubleArray>, p: Int, q: Int) {
        val n = m.size
        val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
        val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
        val c = 1 / sqrt(t * t + 1)
        val s = t * c
        for (k in 0 until n) {
            val mkp = m[k][p]
            val mkq = m[k][q]
            m[k][p] = c * mkp - s * mkq
            m[k][q] = s * mkp + c * mkq
        }
        for (k in 0 until n) {
            val mpk = m[p][k]
            val mqk = m[q][k]
            m[p][k] = c * mpk - s * mqk
            m[q][k] = s * mpk + c * mqk
        }
        for (k in 0 until n) {
            val vkp = v[k][p]
            val vkq = v[k][q]
            v[k][p] = c * vkp - s * vkq
            v[k][q] = s * vkp + c * vkq
        }
    }
}
