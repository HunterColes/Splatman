package com.huntercoles.splatman.splat.sfm.vi

import com.huntercoles.splatman.splat.sfm.ImuPriors
import com.huntercoles.splatman.splat.sfm.vi.Mat3.add
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mtv
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mul
import com.huntercoles.splatman.splat.sfm.vi.Mat3.mv
import com.huntercoles.splatman.splat.sfm.vi.Mat3.scale
import com.huntercoles.splatman.splat.sfm.vi.Mat3.skew
import com.huntercoles.splatman.splat.sfm.vi.Mat3.sub
import com.huntercoles.splatman.splat.sfm.vi.Mat3.tmul

/**
 * IMU noise. Densities are continuous-time (`rad/s/sqrt(Hz)`, `m/s^2/sqrt(Hz)`), bias walks in `rad/s^2/sqrt(Hz)` and
 * `m/s^3/sqrt(Hz)`. [gyroBias0] and [accelBias0] (rad/s, m/s^2, S axes) are the turn-on biases the test simulator
 * starts from; the preintegration does not read them.
 */
class ImuNoise(
    val gyroDensity: Double,
    val accelDensity: Double,
    val gyroBiasWalk: Double,
    val accelBiasWalk: Double,
    val gyroBias0: DoubleArray = DoubleArray(3),
    val accelBias0: DoubleArray = DoubleArray(3),
) {
    companion object {
        val NONE = ImuNoise(0.0, 0.0, 0.0, 0.0)

        /** Phone-grade MEMS: gyro 2e-3, accel 2e-2, walks 2e-5 and 5e-4, biases about 0.01 rad/s, 0.1 m/s^2. */
        val PHONE = ImuNoise(
            gyroDensity = 2e-3,
            accelDensity = 2e-2,
            gyroBiasWalk = 2e-5,
            accelBiasWalk = 5e-4,
            gyroBias0 = doubleArrayOf(0.01, -0.008, 0.005),
            accelBias0 = doubleArrayOf(0.08, -0.05, 0.12),
        )

        /**
         * Placeholders for the Pixel's IMU, stated, not measured: gyro 1.7e-4 rad/s/sqrt(Hz), accel 2.0e-3
         * m/s^2/sqrt(Hz), random walks 1e-5 rad/s^2/sqrt(Hz) and 3e-4 m/s^3/sqrt(Hz). The Allan-variance run of the
         * stage 1 gate (ADR 0016 point 9) replaces them.
         */
        val PIXEL_DEFAULT = ImuNoise(
            gyroDensity = 1.7e-4,
            accelDensity = 2.0e-3,
            gyroBiasWalk = 1e-5,
            accelBiasWalk = 3e-4,
        )
    }
}

/**
 * A keyframe's navigation state: `R_GS` (row-major, S into G), position and velocity of S in G, gyro and accel bias
 * (S axes). Its 15-dimensional tangent is ordered `[phi, p, v, bg, ba]`.
 */
class NavState(
    val r: DoubleArray,
    val p: DoubleArray,
    val v: DoubleArray,
    val gyroBias: DoubleArray,
    val accelBias: DoubleArray,
) {
    /** `R Exp(phi)`, the rest additive: the perturbation every Jacobian of [ImuPreintegration] is taken against. */
    fun retract(delta: DoubleArray): NavState {
        require(delta.size == DIM) { "delta must have $DIM entries, got ${delta.size}" }
        fun part(at: Int) = delta.copyOfRange(at, at + 3)
        return NavState(
            mul(r, SO3.exp(part(0))),
            add(p, part(3)),
            add(v, part(6)),
            add(gyroBias, part(9)),
            add(accelBias, part(12)),
        )
    }

    companion object {
        const val DIM = 15
    }
}

/** Preintegrated deltas: rotation (row-major), velocity and position, in the frame S at the window's start. */
class ImuDeltas(val deltaR: DoubleArray, val deltaV: DoubleArray, val deltaP: DoubleArray)

/** Residual Jacobians, 9 x 15 each: rows `[rotation, velocity, position]`, columns the [NavState] tangent. */
class ImuResidualJacobians(val stateI: Array<DoubleArray>, val stateJ: Array<DoubleArray>)

/**
 * IMU preintegration on the manifold (Forster, Carlone, Dellaert, Scaramuzza, T-RO 2017, sections V and VI), double
 * precision, linearised at [gyroBias] and [accelBias] (rad/s, m/s^2, S axes).
 *
 * Each [integrate] step holds the measurement constant over `dt` (pass the mean of the two samples that bracket the
 * step) and is midpoint in rotation: `dR' = dR Exp(w dt)`, `dV' = dV + Rm a dt`, `dP' = dP + dV dt + Rm a dt^2 / 2`,
 * where `Rm = dR Exp(w dt / 2)`, `w = gyro - bg`, `a = accel - ba`. The scheme is second order: noise-free 200 Hz
 * data integrates to about 1e-7 over 0.1 s and a few 1e-6 over 1 s.
 *
 * The 9x9 [covariance] is of the error `[dphi, dv, dp]` (`dR_measured = dR Exp(dphi)`), propagated with the white
 * noise of [noise] (discrete variance `density^2 / dt`). The bias Jacobians give the first-order update of
 * [corrected]: `dR(bg) = dR Exp(dR/dbg (bg - bg0))`, `dV(bg, ba) = dV + dV/dbg (bg - bg0) + dV/dba (ba - ba0)`, and
 * the same for `dP`. Jacobians are row-major 3x3.
 */
class ImuPreintegration(gyroBias: DoubleArray, accelBias: DoubleArray, val noise: ImuNoise) {
    val gyroBias: DoubleArray = gyroBias.copyOf()
    val accelBias: DoubleArray = accelBias.copyOf()

    init {
        require(gyroBias.size == 3 && accelBias.size == 3) { "biases must be 3-vectors" }
    }

    private var r = Mat3.I
    private var vel = DoubleArray(3)
    private var pos = DoubleArray(3)
    private var rBg = DoubleArray(9)
    private var vBg = DoubleArray(9)
    private var vBa = DoubleArray(9)
    private var pBg = DoubleArray(9)
    private var pBa = DoubleArray(9)
    private var cov = DoubleArray(N * N)

    /** Total integrated time, s. */
    var dt: Double = 0.0
        private set

    /** False once a step longer than [ImuPriors.MAX_GAP_NS] was integrated: the window bridges a gap in the data. */
    var valid: Boolean = true
        private set

    val deltaR: DoubleArray get() = r.copyOf()
    val deltaV: DoubleArray get() = vel.copyOf()
    val deltaP: DoubleArray get() = pos.copyOf()
    val dRdBg: DoubleArray get() = rBg.copyOf()
    val dVdBg: DoubleArray get() = vBg.copyOf()
    val dVdBa: DoubleArray get() = vBa.copyOf()
    val dPdBg: DoubleArray get() = pBg.copyOf()
    val dPdBa: DoubleArray get() = pBa.copyOf()

    /** 9x9, order `[dphi, dv, dp]`. */
    val covariance: Array<DoubleArray> get() = Array(N) { i -> cov.copyOfRange(N * i, N * i + N) }

    /** One step of [dtS] seconds (> 0) with the gyro (rad/s) and accel (m/s^2) measurement held over it. */
    fun integrate(dtS: Double, gyro: DoubleArray, accel: DoubleArray) {
        require(dtS > 0.0 && dtS.isFinite()) { "dt must be positive and finite, got $dtS" }
        require(gyro.size == 3 && accel.size == 3) { "gyro and accel must be 3-vectors" }
        if (dtS > MAX_GAP_S) valid = false
        val w = sub(gyro, gyroBias)
        val a = sub(accel, accelBias)
        val eh = SO3.exp(scale(w, dtS / 2))
        val ef = SO3.exp(scale(w, dtS))
        val jh = SO3.rightJacobian(scale(w, dtS / 2))
        val jf = SO3.rightJacobian(scale(w, dtS))
        val rm = mul(r, eh)
        val ra = mul(rm, skew(a))
        val dt2 = dtS * dtS / 2

        propagateCovariance(dtS, ef, eh, jh, jf, rm, ra)

        // d phi_m / d bg, the midpoint rotation's sensitivity; P uses the old V, V the old R.
        val raJm = mul(ra, sub(tmul(eh, rBg), scale(jh, dtS / 2)))
        pBg = sub(add(pBg, scale(vBg, dtS)), scale(raJm, dt2))
        pBa = sub(add(pBa, scale(vBa, dtS)), scale(rm, dt2))
        vBg = sub(vBg, scale(raJm, dtS))
        vBa = sub(vBa, scale(rm, dtS))
        rBg = sub(tmul(ef, rBg), scale(jf, dtS))

        val accG = mv(rm, a)
        pos = add(add(pos, scale(vel, dtS)), scale(accG, dt2))
        vel = add(vel, scale(accG, dtS))
        r = mul(r, ef)
        dt += dtS
    }

    @Suppress("LongParameterList") // The step's shared intermediates, computed once in integrate.
    private fun propagateCovariance(
        dtS: Double,
        ef: DoubleArray,
        eh: DoubleArray,
        jh: DoubleArray,
        jf: DoubleArray,
        rm: DoubleArray,
        ra: DoubleArray,
    ) {
        val dt2 = dtS * dtS / 2
        val raEh = Mat3.mul(ra, Mat3.transpose(eh))
        val raJh = scale(mul(ra, jh), dtS / 2)
        val am = DoubleArray(N * N)
        putBlock(am, N, 0, 0, Mat3.transpose(ef))
        putBlock(am, N, 3, 0, scale(raEh, -dtS))
        putBlock(am, N, 6, 0, scale(raEh, -dt2))
        putBlock(am, N, 3, 3, Mat3.I)
        putBlock(am, N, 6, 3, scale(Mat3.I, dtS))
        putBlock(am, N, 6, 6, Mat3.I)
        val bm = DoubleArray(N * NOISE)
        putBlock(bm, NOISE, 0, 0, scale(jf, dtS))
        putBlock(bm, NOISE, 3, 0, scale(raJh, -dtS))
        putBlock(bm, NOISE, 6, 0, scale(raJh, -dt2))
        putBlock(bm, NOISE, 3, 3, scale(rm, dtS))
        putBlock(bm, NOISE, 6, 3, scale(rm, dt2))
        val qg = noise.gyroDensity * noise.gyroDensity / dtS
        val qa = noise.accelDensity * noise.accelDensity / dtS
        val next = DoubleArray(N * N)
        for (i in 0 until N) {
            for (j in 0 until N) {
                var s = 0.0
                for (k in 0 until N) {
                    val aik = am[N * i + k]
                    if (aik == 0.0) continue
                    for (l in 0 until N) s += aik * cov[N * k + l] * am[N * j + l]
                }
                for (k in 0 until NOISE) s += bm[NOISE * i + k] * bm[NOISE * j + k] * if (k < 3) qg else qa
                next[N * i + j] = s
            }
        }
        cov = next
    }

    /** The deltas after the first-order update to biases [bg], [ba] (no re-integration). */
    fun corrected(bg: DoubleArray, ba: DoubleArray): ImuDeltas {
        val dbg = sub(bg, gyroBias)
        val dba = sub(ba, accelBias)
        return ImuDeltas(
            mul(r, SO3.exp(mv(rBg, dbg))),
            add(add(vel, mv(vBg, dbg)), mv(vBa, dba)),
            add(add(pos, mv(pBg, dbg)), mv(pBa, dba)),
        )
    }

    /**
     * Forster eq. 45 with the biases of [stateI]: `[Log(dR^T R_i^T R_j), R_i^T (v_j - v_i - g dt) - dV,
     * R_i^T (p_j - p_i - v_i dt - g dt^2 / 2) - dP]`, [gravityG] in G (m/s^2).
     */
    fun residual(stateI: NavState, stateJ: NavState, gravityG: DoubleArray): DoubleArray = Terms(
        stateI,
        stateJ,
        gravityG,
    ).residual

    /** Jacobians of [residual] against [NavState.retract] of each state; the bias columns of state j are zero. */
    fun residualJacobians(stateI: NavState, stateJ: NavState, gravityG: DoubleArray): ImuResidualJacobians {
        val t = Terms(stateI, stateJ, gravityG)
        val ri = stateI.r
        val riT = Mat3.transpose(ri)
        val jrInv = SO3.rightJacobianInverse(t.rotation)
        val ji = Array(N) { DoubleArray(NavState.DIM) }
        val jj = Array(N) { DoubleArray(NavState.DIM) }
        putBlock(ji, 0, 0, scale(mul(jrInv, tmul(stateJ.r, ri)), -1.0))
        val dRdbgNow = mul(SO3.rightJacobian(mv(rBg, t.dbg)), rBg)
        putBlock(ji, 0, 9, scale(mul(jrInv, mul(Mat3.transpose(t.e), dRdbgNow)), -1.0))
        putBlock(ji, 3, 0, skew(mtv(ri, t.u)))
        putBlock(ji, 3, 6, scale(riT, -1.0))
        putBlock(ji, 3, 9, scale(vBg, -1.0))
        putBlock(ji, 3, 12, scale(vBa, -1.0))
        putBlock(ji, 6, 0, skew(mtv(ri, t.w)))
        putBlock(ji, 6, 3, scale(riT, -1.0))
        putBlock(ji, 6, 6, scale(riT, -dt))
        putBlock(ji, 6, 9, scale(pBg, -1.0))
        putBlock(ji, 6, 12, scale(pBa, -1.0))
        putBlock(jj, 0, 0, jrInv)
        putBlock(jj, 3, 6, riT)
        putBlock(jj, 6, 3, riT)
        return ImuResidualJacobians(ji, jj)
    }

    /** The residual's shared pieces: `E = dR_c^T R_i^T R_j`, `u`, `w` (Forster eq. 45) and the bias offset. */
    private inner class Terms(stateI: NavState, stateJ: NavState, g: DoubleArray) {
        val dbg = sub(stateI.gyroBias, gyroBias)
        private val c = corrected(stateI.gyroBias, stateI.accelBias)
        val e = tmul(c.deltaR, tmul(stateI.r, stateJ.r))
        val rotation = SO3.log(e)
        val u = DoubleArray(3) { stateJ.v[it] - stateI.v[it] - g[it] * dt }
        val w = DoubleArray(3) { stateJ.p[it] - stateI.p[it] - stateI.v[it] * dt - g[it] * dt * dt / 2 }
        val residual = rotation + sub(mtv(stateI.r, u), c.deltaV) + sub(mtv(stateI.r, w), c.deltaP)
    }

    companion object {
        /** [ImuPriors.MAX_GAP_NS] in seconds, plus a nanosecond so a step of exactly the limit stays valid. */
        val MAX_GAP_S: Double = (ImuPriors.MAX_GAP_NS + 1) / 1e9

        private const val N = 9
        private const val NOISE = 6

        private fun putBlock(m: DoubleArray, cols: Int, row: Int, col: Int, b: DoubleArray) {
            for (i in 0..2) for (j in 0..2) m[cols * (row + i) + col + j] = b[3 * i + j]
        }

        private fun putBlock(m: Array<DoubleArray>, row: Int, col: Int, b: DoubleArray) {
            for (i in 0..2) for (j in 0..2) m[row + i][col + j] = b[3 * i + j]
        }
    }
}
