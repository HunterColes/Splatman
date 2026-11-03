package com.huntercoles.splatman.splat.sfm.vi

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic visual-inertial scene for the VIO tests (ADR 0016 point 8). Frames as ADR 0016 point 5: the world G is
 * gravity-aligned (+Z up, gravity `(0, 0, -GRAVITY)`), the body S has Android sensor axes (x right, y up, z out of
 * the screen), the camera C has OpenCV axes (x right, y down, z forward). Rotations are row-major 3x3 DoubleArrays,
 * `R_AB` maps B coordinates into A. Double precision throughout, no Android types, randomness from the seed only.
 *
 * The body trajectory is analytic (sums of sinusoids and C2 quintic ramps), so position, velocity, acceleration,
 * rotation and body angular rate are exact at any time; the IMU samples come from them:
 * `gyro = omega_S + b_g + n_g`, `accel = R_GS^T (a_G - g_G) + b_a + n_a`, where `n` is white noise of the stated
 * density (`sigma = density * sqrt(rate)`) and the biases random-walk (`b += walk * sqrt(dt) * N(0, 1)` per sample).
 *
 * The camera pose at time t is `R_GC = R_GS(t) R_SC`, `p_GC = p_GS(t) + R_GS(t) p_SC` (lever arm in S). A keyframe
 * stamped `tCam` on the camera clock was exposed at `t_imu = tCam + td`; with rolling shutter a row `v` is exposed at
 * `tCam + td + (v - h/2) * readout / h` (ADR 0016 point 3), solved per point by fixed-point iteration.
 */
class ViSim(
    val seed: Long,
    val scenario: ViScenario,
    val config: ViSimConfig = ViSimConfig(),
) {
    val duration: Double get() = scenario.duration
    val gravity: DoubleArray = doubleArrayOf(0.0, 0.0, -GRAVITY)

    private val trajectory: Trajectory = scenario.trajectory(Random(seed * 31 + 1))

    /** World points in G, metres. */
    val points: Array<DoubleArray> = scenario.points(Random(seed * 31 + 2), config.pointCount)

    /** IMU samples at `config.imuRate` from t = 0 to [duration] inclusive. */
    val imu: List<ImuSample> = makeImu(Random(seed * 31 + 3))

    /** Keyframes at `config.keyframeRate` (camera clock) from tCam = 0 to [duration] inclusive. */
    val keyframes: List<ViKeyframe> = makeKeyframes(Random(seed * 31 + 4))

    /** The exact body state at IMU-clock time [t]. */
    fun state(t: Double): ViState = trajectory.state(t)

    /** Camera pose `R_GC`, `p_GC` at IMU-clock time [t]. */
    fun cameraPose(t: Double): Pair<DoubleArray, DoubleArray> {
        val s = state(t)
        return Pair(M3.mul(s.rGS, config.rSC), M3.add(s.p, M3.mv(s.rGS, config.pSC)))
    }

    /** Pinhole projection of [x] (G) by the camera at IMU-clock time [t]: `u, v, z_C`, no bounds check. */
    fun project(x: DoubleArray, t: Double): DoubleArray {
        val (r, p) = cameraPose(t)
        val c = M3.mtv(r, M3.sub(x, p))
        val k = config.intrinsics
        val u = k.fx.toDouble() * c[0] / c[2] + k.cx.toDouble()
        val v = k.fy.toDouble() * c[1] / c[2] + k.cy.toDouble()
        return doubleArrayOf(u, v, c[2])
    }

    private fun makeImu(rnd: Random): List<ImuSample> {
        val n = config.noise
        val dt = 1.0 / config.imuRate
        val count = (duration * config.imuRate).toLong().toInt()
        val sg = n.gyroDensity * sqrt(config.imuRate)
        val sa = n.accelDensity * sqrt(config.imuRate)
        val bg = n.gyroBias0.copyOf()
        val ba = n.accelBias0.copyOf()
        return (0..count).map { i ->
            val t = i * dt
            val s = state(t)
            val gyro = DoubleArray(3) { s.omegaS[it] + bg[it] + sg * rnd.nextGaussian() }
            val f = M3.mtv(s.rGS, M3.sub(s.a, gravity))
            val accel = DoubleArray(3) { f[it] + ba[it] + sa * rnd.nextGaussian() }
            val sample = ImuSample(t, gyro, accel, bg.copyOf(), ba.copyOf())
            for (j in 0..2) {
                bg[j] += n.gyroBiasWalk * sqrt(dt) * rnd.nextGaussian()
                ba[j] += n.accelBiasWalk * sqrt(dt) * rnd.nextGaussian()
            }
            sample
        }
    }

    private fun makeKeyframes(rnd: Random): List<ViKeyframe> {
        val k = config.intrinsics
        val h = k.height.toDouble()
        val count = (duration * config.keyframeRate).toLong().toInt()
        return (0..count).map { i ->
            val tCam = i / config.keyframeRate
            val centre = tCam + config.td
            val (r, p) = cameraPose(centre)
            val obs = ArrayList<ViObservation>()
            for ((id, x) in points.withIndex()) {
                var tRow = centre
                var c = project(x, tRow)
                if (c[2] < MIN_DEPTH) continue
                if (config.readout != 0.0) {
                    // Rows clamped to the image, so a point far off-frame cannot send the time away; for a point that
                    // ends up inside the image the clamp is inactive at the fixed point.
                    repeat(MAX_SHUTTER_ITERATIONS) {
                        val next = centre + (c[1].coerceIn(0.0, h - 1) - h / 2) * config.readout / h
                        if (next == tRow) return@repeat
                        tRow = next
                        c = project(x, tRow)
                    }
                }
                if (c[2] < MIN_DEPTH || c[0] < 0 || c[1] < 0 || c[0] > k.width - 1 || c[1] > k.height - 1) continue
                val nu = config.pixelSigma * rnd.nextGaussian()
                val nv = config.pixelSigma * rnd.nextGaussian()
                obs += ViObservation(id, c[0] + nu, c[1] + nv, c[0], c[1], tRow)
            }
            ViKeyframe(i, tCam, r, p, obs)
        }
    }

    companion object {
        const val GRAVITY = 9.81
        const val MIN_DEPTH = 0.05
        private const val MAX_SHUTTER_ITERATIONS = 50

        /** Back camera of a phone held in portrait: C x = S x, C y = -S y, C z = -S z. */
        val R_SC_PORTRAIT: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

        /** 640 x 480, fx = fy = 500 px, principal point at the image centre (pixel-centre convention). */
        val DEFAULT_INTRINSICS = CameraIntrinsics(500f, 500f, 319.5f, 239.5f, 640, 480)
    }
}

/** Scenario shapes. Durations in s. */
enum class ViScenario(val duration: Double) {
    /** Orbit an object at the origin: 0.5 m radius, one turn in 30 s, 0.3 m above it, looking at it. */
    ORBIT(30.0),

    /** Walk an ellipse (1.2 m x 0.8 m) inside a 4 m x 3 m room at eye height, turning to look around. */
    ROOM(30.0),

    /** Still for 2 s, then a 1 m sideways move (C2 ramp over 3 s) facing a wall, then still. */
    STATIC(6.0),
    ;

    internal fun trajectory(rnd: Random): Trajectory {
        fun ph() = rnd.nextDouble() * 2 * PI
        val turn = 1.0 / 30.0
        return when (this) {
            ORBIT -> Trajectory(
                x = Sines(0.0, 0.0, Wave(0.5, turn, PI / 2), Wave(0.02, 0.37, ph())),
                y = Sines(0.0, 0.0, Wave(0.5, turn, 0.0), Wave(0.02, 0.29, ph())),
                z = Sines(0.3, 0.0, Wave(0.03, 0.2, ph())),
                yaw = Sines(PI, 2 * PI * turn, Wave(0.05, 0.23, ph())),
                pitch = Sines(0.54, 0.0, Wave(0.05, 0.31, ph())),
                roll = Sines(0.0, 0.0, Wave(0.04, 0.41, ph())),
            )
            ROOM -> Trajectory(
                x = Sines(0.0, 0.0, Wave(1.2, turn, PI / 2), Wave(0.05, 0.17, ph())),
                y = Sines(0.0, 0.0, Wave(0.8, turn, 0.0), Wave(0.05, 0.13, ph())),
                z = Sines(1.4, 0.0, Wave(0.02, 1.6, ph())),
                yaw = Sines(PI / 2, 2 * PI * turn, Wave(0.8, 1.0 / 9.0, ph()), Wave(0.1, 0.5, ph())),
                pitch = Sines(0.0, 0.0, Wave(0.08, 0.21, ph())),
                roll = Sines(0.0, 0.0, Wave(0.03, 0.8, ph())),
            )
            STATIC -> Trajectory(
                x = Sines(0.0, 0.0),
                y = Ramp(0.0, 1.0, 2.0, 3.0),
                z = Sines(1.2, 0.0),
                yaw = Ramp(0.0, 0.2, 2.0, 3.0),
                pitch = Sines(0.0, 0.0),
                roll = Sines(0.0, 0.0),
            )
        }
    }

    internal fun points(rnd: Random, n: Int): Array<DoubleArray> {
        fun u(a: Double, b: Double) = a + (b - a) * rnd.nextDouble()
        return when (this) {
            ORBIT -> Array(n) { doubleArrayOf(u(-0.15, 0.15), u(-0.15, 0.15), u(-0.15, 0.15)) }
            ROOM -> Array(n) {
                // Four walls of x in [-2, 2], y in [-1.5, 1.5], z in [0, 2.5], and the floor; uniform by area.
                val walls = doubleArrayOf(3.0 * 2.5, 3.0 * 2.5, 4.0 * 2.5, 4.0 * 2.5, 4.0 * 3.0)
                var pick = u(0.0, walls.sum())
                var face = 0
                while (face < walls.size - 1 && pick > walls[face]) pick -= walls[face++]
                when (face) {
                    0 -> doubleArrayOf(-2.0, u(-1.5, 1.5), u(0.0, 2.5))
                    1 -> doubleArrayOf(2.0, u(-1.5, 1.5), u(0.0, 2.5))
                    2 -> doubleArrayOf(u(-2.0, 2.0), -1.5, u(0.0, 2.5))
                    3 -> doubleArrayOf(u(-2.0, 2.0), 1.5, u(0.0, 2.5))
                    else -> doubleArrayOf(u(-2.0, 2.0), u(-1.5, 1.5), 0.0)
                }
            }
            STATIC -> Array(n) { doubleArrayOf(2.5, u(-2.0, 3.0), u(0.0, 2.4)) }
        }
    }
}

@Suppress("LongParameterList") // A test fixture's knobs, every one defaulted and named at the call site.
class ViSimConfig(
    val imuRate: Double = 200.0,
    val keyframeRate: Double = 10.0,
    val noise: ImuNoise = ImuNoise.NONE,
    /** Camera-to-IMU rotation `R_SC`. */
    val rSC: DoubleArray = ViSim.R_SC_PORTRAIT,
    /** Lever arm: the camera centre in S, metres. */
    val pSC: DoubleArray = doubleArrayOf(0.01, 0.03, -0.005),
    /** Time offset, s: `t_imu = t_cam + td`. */
    val td: Double = 0.0,
    /** Rolling-shutter readout of the whole frame, s; 0 is a global shutter. */
    val readout: Double = 0.0,
    /** Pixel noise standard deviation, px, on u and v. */
    val pixelSigma: Double = 0.0,
    val intrinsics: CameraIntrinsics = ViSim.DEFAULT_INTRINSICS,
    val pointCount: Int = 200,
)

/** One IMU sample on the IMU clock with the true biases it carries. */
class ImuSample(
    val t: Double,
    val gyro: DoubleArray,
    val accel: DoubleArray,
    val gyroBias: DoubleArray,
    val accelBias: DoubleArray,
)

/** Exact body state: `R_GS`, position, velocity and acceleration in G, angular rate in S. */
class ViState(
    val t: Double,
    val rGS: DoubleArray,
    val p: DoubleArray,
    val v: DoubleArray,
    val a: DoubleArray,
    val omegaS: DoubleArray,
)

/** A pixel observation: noisy `u, v`, noise-free `uClean, vClean`, and the IMU-clock time its row was exposed. */
class ViObservation(
    val point: Int,
    val u: Double,
    val v: Double,
    val uClean: Double,
    val vClean: Double,
    val tRow: Double,
)

/** A keyframe stamped [tCam] (camera clock); [rGC], [pGC] are the camera pose at the centre row, `tCam + td`. */
class ViKeyframe(
    val index: Int,
    val tCam: Double,
    val rGC: DoubleArray,
    val pGC: DoubleArray,
    val observations: List<ViObservation>,
)

/** One scalar of the trajectory with exact first and second derivatives. */
internal interface Channel {
    fun value(t: Double): Double
    fun d1(t: Double): Double
    fun d2(t: Double): Double
}

internal class Wave(val amp: Double, val hz: Double, val phase: Double)

/** `c + rate t + sum amp sin(2 pi hz t + phase)`. */
internal class Sines(private val c: Double, private val rate: Double, private vararg val waves: Wave) : Channel {
    override fun value(t: Double) = c + rate * t + waves.sumOf { it.amp * sin(2 * PI * it.hz * t + it.phase) }
    override fun d1(t: Double) = rate + waves.sumOf { val w = 2 * PI * it.hz; it.amp * w * cos(w * t + it.phase) }
    override fun d2(t: Double) = -waves.sumOf { val w = 2 * PI * it.hz; it.amp * w * w * sin(w * t + it.phase) }
}

/** From [from] to [to] over [t0, t0 + length] by the quintic smoothstep `10s^3 - 15s^4 + 6s^5` (C2), flat outside. */
internal class Ramp(
    private val from: Double,
    private val to: Double,
    private val t0: Double,
    private val length: Double,
) : Channel {
    private fun s(t: Double) = ((t - t0) / length).coerceIn(0.0, 1.0)
    override fun value(t: Double): Double {
        val s = s(t)
        return from + (to - from) * s * s * s * (10 - 15 * s + 6 * s * s)
    }

    override fun d1(t: Double): Double {
        val s = s(t)
        return (to - from) * 30 * s * s * (1 - s) * (1 - s) / length
    }

    override fun d2(t: Double): Double {
        val s = s(t)
        return (to - from) * 60 * s * (1 - s) * (1 - 2 * s) / (length * length)
    }
}

/**
 * Position channels in G and Z-Y-X Euler angles of the frame B (`R_GB = Rz(yaw) Ry(pitch) Rx(roll)`), whose x axis is
 * the camera's view direction; `R_GS = R_GB R_BS` with the phone upright (S y = B z, S z = -B x).
 */
internal class Trajectory(
    val x: Channel,
    val y: Channel,
    val z: Channel,
    val yaw: Channel,
    val pitch: Channel,
    val roll: Channel,
) {
    fun state(t: Double): ViState {
        val ps = yaw.value(t); val th = pitch.value(t); val ph = roll.value(t)
        val dps = yaw.d1(t); val dth = pitch.d1(t); val dph = roll.d1(t)
        val rGB = M3.mul(M3.mul(M3.rotZ(ps), M3.rotY(th)), M3.rotX(ph))
        val omegaB = doubleArrayOf(
            dph - dps * sin(th),
            dth * cos(ph) + dps * cos(th) * sin(ph),
            -dth * sin(ph) + dps * cos(th) * cos(ph),
        )
        return ViState(
            t = t,
            rGS = M3.mul(rGB, R_BS),
            p = doubleArrayOf(x.value(t), y.value(t), z.value(t)),
            v = doubleArrayOf(x.d1(t), y.d1(t), z.d1(t)),
            a = doubleArrayOf(x.d2(t), y.d2(t), z.d2(t)),
            omegaS = M3.mtv(R_BS, omegaB),
        )
    }

    companion object {
        /** S axes in B: S x = -B y, S y = B z, S z = -B x. */
        val R_BS = doubleArrayOf(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }
}

/** Row-major 3x3 and 3-vector helpers, double precision. */
object M3 {
    val I: DoubleArray get() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3; val j = k % 3
        a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j]
    }

    fun transpose(a: DoubleArray) = DoubleArray(9) { k -> a[(k % 3) * 3 + k / 3] }
    fun mv(a: DoubleArray, v: DoubleArray) =
        DoubleArray(3) { i -> a[3 * i] * v[0] + a[3 * i + 1] * v[1] + a[3 * i + 2] * v[2] }
    fun mtv(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> a[i] * v[0] + a[3 + i] * v[1] + a[6 + i] * v[2] }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] - b[it] }
    fun scale(a: DoubleArray, s: Double) = DoubleArray(a.size) { a[it] * s }
    fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })
    fun skew(w: DoubleArray) = doubleArrayOf(0.0, -w[2], w[1], w[2], 0.0, -w[0], -w[1], w[0], 0.0)

    fun rotX(a: Double) = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
    fun rotY(a: Double) = doubleArrayOf(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
    fun rotZ(a: Double) = doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)

    /** SO(3) exponential (Rodrigues), with the second-order series below 1e-8 rad. */
    fun exp(w: DoubleArray): DoubleArray {
        val th = norm(w)
        val k = skew(w)
        val k2 = mul(k, k)
        val small = th < 1e-8
        val a = if (small) 1.0 - th * th / 6 else sin(th) / th
        val b = if (small) 0.5 - th * th / 24 else (1 - cos(th)) / (th * th)
        return DoubleArray(9) { I[it] + a * k[it] + b * k2[it] }
    }

    /** Angle of `a^T b`, rad, from its skew part (exact for small angles; below pi/2). */
    fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        val d = mul(transpose(a), b)
        return norm(doubleArrayOf(d[7] - d[5], d[2] - d[6], d[3] - d[1])) / 2
    }

    fun maxAbsDiff(a: DoubleArray, b: DoubleArray) = a.indices.maxOf { abs(a[it] - b[it]) }
}
