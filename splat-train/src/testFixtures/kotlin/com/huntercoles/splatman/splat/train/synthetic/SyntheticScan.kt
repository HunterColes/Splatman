package com.huntercoles.splatman.splat.train.synthetic

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.RgbImage
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.sfm.ImuSamples
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Per-sample IMU errors, IMU frame: zero-mean Gaussian noise of the given standard deviation plus a constant bias.
 * Gyro rad/s, accelerometer m/s^2.
 */
data class ImuNoise(
    val gyroStdRadS: Float = 0f,
    val gyroBiasRadS: Vec3 = Vec3.ZERO,
    val accelStdMS2: Float = 0f,
    val accelBiasMS2: Vec3 = Vec3.ZERO,
) {
    init {
        require(gyroStdRadS >= 0f && accelStdMS2 >= 0f) { "noise std must be >= 0" }
    }

    companion object {
        val NONE = ImuNoise()
    }
}

/**
 * How the synthetic camera moves around the subject, over [durationS] seconds. Distances are in units of the scan's
 * [SyntheticScan.orbitRadius] (the distance that fits the subject in view); angles in degrees; azimuth 0 puts the
 * camera on the subject's +Z side, elevation is above the subject's centre. Every path is smooth (C-infinity), so the
 * gyro and accelerometer it implies are well defined.
 */
sealed interface ScanPath {
    val durationS: Double

    /** A level circle around the subject, always looking at its centre, no roll. */
    data class Orbit(
        val turns: Double = 1.0,
        val elevationDeg: Double = 20.0,
        override val durationS: Double = 12.0,
        val startAzimuthDeg: Double = 0.0,
    ) : ScanPath {
        init {
            require(durationS > 0.0) { "durationS must be > 0, got $durationS" }
            require(elevationDeg > -80.0 && elevationDeg < 80.0) { "elevationDeg must be in (-80, 80)" }
        }
    }

    /**
     * A hand-held-like arc of [arcDeg] centred on [centreAzimuthDeg]: the distance sways by +-[sway] of the radius,
     * the height bobs, and the camera wobbles off the look-at by up to [wobbleDeg] in yaw, pitch and roll (two
     * sinusoids each, 0.3..1.2 Hz, phases and frequencies drawn from the scan's seed).
     */
    data class WobblyArc(
        val arcDeg: Double = 120.0,
        val elevationDeg: Double = 15.0,
        override val durationS: Double = 8.0,
        val wobbleDeg: Double = 4.0,
        val sway: Double = 0.08,
        val centreAzimuthDeg: Double = 0.0,
    ) : ScanPath {
        init {
            require(durationS > 0.0) { "durationS must be > 0, got $durationS" }
            require(elevationDeg > -60.0 && elevationDeg < 60.0) { "elevationDeg must be in (-60, 60)" }
            require(wobbleDeg in 0.0..20.0) { "wobbleDeg must be in 0..20, got $wobbleDeg" }
            require(sway in 0.0..0.5) { "sway must be in 0..0.5, got $sway" }
        }
    }
}

/** One rendered view: its image, the true `T_W_Cgl` (ADR 0003) and its timestamp on the IMU's clock. */
class SyntheticFrame(val index: Int, val timestampNs: Long, val image: RgbImage, val pose: CameraPose)

/**
 * A synthetic scan with ground truth (#224): views of a cloud rendered by the CPU [Rasterizer.forward] from known
 * poses, with the gyro and accelerometer a phone moving that way would report.
 *
 * Conventions (ADR 0003, ADR 0012):
 * - poses are `T_W_Cgl`; W is +Y up, so gravity is -Y.
 * - [rotationCcvImu] is `R_Ccv_Imu`: it maps a vector in the IMU (device sensor) frame into the OpenCV camera frame
 *   Ccv; `R_W_Imu = R_W_Cgl * R_Cgl_Ccv * R_Ccv_Imu`, `R_Cgl_Ccv = diag(1, -1, -1)`.
 * - [gyro] is the body angular rate in the IMU frame, rad/s: `d/dt R_W_Imu = R_W_Imu [omega]x`. Integrated from one
 *   frame's timestamp to another's it gives `R_Imu(t0)_Imu(t1)` ([integrateGyro]).
 * - [accel] is the specific force in the IMU frame, m/s^2: `R_W_Imu^T (a_W + g up)`, the camera's true linear
 *   acceleration plus the reaction to gravity ([GRAVITY_MS2] along +Y of W), as an Android accelerometer reads it.
 * - both IMU streams are sampled at [IMU_RATE_HZ] from the first frame's timestamp to the last's, same clock.
 *
 * Everything is a pure function of the arguments: the same seed gives the same images, poses and samples.
 */
class SyntheticScan(
    val frames: List<SyntheticFrame>,
    val intrinsics: CameraIntrinsics,
    val gyro: ImuSamples,
    val accel: ImuSamples,
    val rotationCcvImu: Quat,
    /** Centre of the subject (2..98th percentile box of the splat centres), world frame W. */
    val subjectCentre: Vec3,
    /** Distance from [subjectCentre] at which the subject fills the view; the paths' unit of length. */
    val orbitRadius: Float,
) {
    /** The true poses, index-aligned with [frames]. */
    val truth: List<CameraPose> get() = frames.map { it.pose }

    /** `R_W_Imu` of frame [i]: its true camera rotation composed with [rotationCcvImu]. */
    fun imuRotation(i: Int): Quat = frames[i].pose.rotation * CGL_FROM_CCV * rotationCcvImu

    companion object {
        const val IMU_RATE_HZ = 200
        const val GRAVITY_MS2 = 9.80665
        private const val IMU_PERIOD_NS = 1_000_000_000L / IMU_RATE_HZ
        private const val START_NS = 1_000_000_000L
        private const val NOISE_SALT = 0x5EED_1A2BL
        private const val FIT_MARGIN = 1.15
        private const val ROTATION_STEP_S = 1e-4
        private const val ACCEL_STEP_S = 1e-3

        /** `R_Cgl_Ccv`: 180 degrees about X. */
        val CGL_FROM_CCV = Quat(0f, 1f, 0f, 0f)

        /**
         * Default `R_Ccv_Imu`: a rear camera whose sensor's long side runs along the device's Y (sensor orientation
         * 90): image +x = device -Y, image +y = device -X, optical axis +z = device -Z (out of the back).
         * The 180-degree turn about (1, -1, 0) / sqrt 2.
         */
        val DEFAULT_ROTATION_CCV_IMU = Quat(0f, (1 / sqrt(2.0)).toFloat(), (-1 / sqrt(2.0)).toFloat(), 0f)

        /**
         * Renders [frames] views of [cloud] along [path], evenly spaced in time from its start to its end, each
         * `intrinsics.width x intrinsics.height`, and the IMU streams over the same span. [seed] drives the
         * [ScanPath.WobblyArc] wobble and the [noise]; nothing else is random.
         */
        @Suppress("LongParameterList")
        fun render(
            cloud: SplatCloud,
            frames: Int,
            intrinsics: CameraIntrinsics,
            path: ScanPath,
            seed: Long,
            noise: ImuNoise = ImuNoise.NONE,
            rotationCcvImu: Quat = DEFAULT_ROTATION_CCV_IMU,
        ): SyntheticScan {
            require(frames >= 1) { "frames must be >= 1, got $frames" }
            require(cloud.count > 0) { "cloud must not be empty" }
            val (centre, halfSpan) = subjectBox(cloud)
            val halfFov = min(
                atan((intrinsics.width / 2.0) / intrinsics.fx),
                atan((intrinsics.height / 2.0) / intrinsics.fy),
            )
            val radius = halfSpan * FIT_MARGIN / tan(halfFov) + halfSpan
            val trajectory = Trajectory(path, centre, radius, Random(seed))
            val qCcvImu = D.q(rotationCcvImu.normalized())
            val durationNs = (path.durationS * 1e9).roundToLong()

            val out = ArrayList<SyntheticFrame>(frames)
            val buffers = RenderBuffers(intrinsics.width, intrinsics.height)
            Rasterizer().use { raster ->
                for (i in 0 until frames) {
                    val tNs = if (frames == 1) 0L else (durationNs.toDouble() * i / (frames - 1)).roundToLong()
                    val (q, eye) = trajectory.at(tNs / 1e9)
                    val pose = CameraPose(D.quat(q), Vec3(eye[0].toFloat(), eye[1].toFloat(), eye[2].toFloat()))
                    raster.forward(cloud, intrinsics, pose, buffers)
                    out += SyntheticFrame(i, START_NS + tNs, toImage(buffers), pose)
                }
            }

            val samples = (durationNs / IMU_PERIOD_NS).toInt() + 1
            val times = LongArray(samples) { START_NS + it * IMU_PERIOD_NS }
            val gyro = FloatArray(3 * samples)
            val acc = FloatArray(3 * samples)
            val rnd = Random(seed xor NOISE_SALT)
            for (k in 0 until samples) {
                val t = k * IMU_PERIOD_NS / 1e9
                val w = bodyRate(trajectory, qCcvImu, t)
                val f = specificForce(trajectory, qCcvImu, t)
                for (a in 0 until 3) {
                    gyro[3 * k + a] = (w[a] + noise.gyroStdRadS * rnd.nextGaussian() + noise.gyroBiasRadS[a]).toFloat()
                    acc[3 * k + a] = (f[a] + noise.accelStdMS2 * rnd.nextGaussian() + noise.accelBiasMS2[a]).toFloat()
                }
            }
            return SyntheticScan(
                out, intrinsics, ImuSamples(times, gyro), ImuSamples(times.copyOf(), acc), rotationCcvImu.normalized(),
                Vec3(centre[0].toFloat(), centre[1].toFloat(), centre[2].toFloat()), radius.toFloat(),
            )
        }

        /**
         * `R_Imu(fromNs)_Imu(toNs)` from [gyro] (body rates, IMU frame), the rate interpolated linearly between
         * samples and each piece integrated at its midpoint rate. [fromNs] <= [toNs], both inside the samples' span.
         */
        fun integrateGyro(gyro: ImuSamples, fromNs: Long, toNs: Long): Quat {
            val ts = gyro.timestampsNs
            require(gyro.count >= 2) { "need at least 2 gyro samples" }
            require(fromNs <= toNs && fromNs >= ts[0] && toNs <= ts[gyro.count - 1]) {
                "interval $fromNs..$toNs outside the gyro span ${ts[0]}..${ts[gyro.count - 1]}"
            }
            var q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            for (k in 0 until gyro.count - 1) {
                val a = max(fromNs, ts[k])
                val b = min(toNs, ts[k + 1])
                if (b <= a) continue
                val mid = (a + b) / 2.0
                val s = (mid - ts[k]) / (ts[k + 1] - ts[k]).toDouble()
                val dt = (b - a) / 1e9
                val v = DoubleArray(3) { (gyro.xyz[3 * k + it] * (1 - s) + gyro.xyz[3 * (k + 1) + it] * s) * dt }
                q = D.mul(q, D.exp(v))
            }
            return D.quat(q)
        }

        private fun bodyRate(tr: Trajectory, qCcvImu: DoubleArray, t: Double): DoubleArray {
            val h = ROTATION_STEP_S
            val before = imuRotation(tr.at(t - h).first, qCcvImu)
            val after = imuRotation(tr.at(t + h).first, qCcvImu)
            val v = D.log(D.mul(D.conj(before), after))
            return DoubleArray(3) { v[it] / (2 * h) }
        }

        private fun specificForce(tr: Trajectory, qCcvImu: DoubleArray, t: Double): DoubleArray {
            val h = ACCEL_STEP_S
            val (q, p) = tr.at(t)
            val pb = tr.at(t - h).second
            val pa = tr.at(t + h).second
            val aW = DoubleArray(3) { (pa[it] - 2 * p[it] + pb[it]) / (h * h) }
            aW[1] += GRAVITY_MS2
            return D.rotate(D.conj(imuRotation(q, qCcvImu)), aW)
        }

        private fun imuRotation(qWCgl: DoubleArray, qCcvImu: DoubleArray): DoubleArray =
            D.mul(D.mul(qWCgl, D.q(CGL_FROM_CCV)), qCcvImu)

        private operator fun Vec3.get(a: Int): Float = when (a) {
            0 -> x
            1 -> y
            else -> z
        }

        /** Centre and largest half extent of the 2..98th percentile box of the splat centres. */
        private fun subjectBox(cloud: SplatCloud): Pair<DoubleArray, Double> {
            val centre = DoubleArray(3)
            var half = 0.0
            for (a in 0 until 3) {
                val v = FloatArray(cloud.count) { cloud.positions[3 * it + a] }.also { it.sort() }
                val lo = v[floor(0.02 * (v.size - 1)).toInt()]
                val hi = v[floor(0.98 * (v.size - 1)).toInt()]
                centre[a] = (lo + hi) / 2.0
                half = max(half, (hi - lo) / 2.0)
            }
            return centre to max(half, 1e-3)
        }

        private fun toImage(b: RenderBuffers): RgbImage {
            val n = b.width * b.height
            val rgba = ByteArray(4 * n)
            for (p in 0 until n) {
                for (c in 0 until 3) rgba[4 * p + c] = (b.color[3 * p + c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                rgba[4 * p + 3] = 255.toByte()
            }
            return RgbImage(b.width, b.height, rgba)
        }
    }
}

/** The analytic camera path of one scan: `T_W_Cgl` at time t (seconds from the first frame), in double. */
private class Trajectory(private val path: ScanPath, private val c: DoubleArray, private val r: Double, rnd: Random) {
    // Drawn whatever the path, so the draws never depend on the path kind: frequency (Hz) and phase, 8 of each.
    private val freq = DoubleArray(8) { 0.3 + 0.9 * rnd.nextDouble() }
    private val phase = DoubleArray(8) { 2 * PI * rnd.nextDouble() }

    private fun wave(k: Int, t: Double): Double = sin(2 * PI * freq[k] * t + phase[k])

    fun at(t: Double): Pair<DoubleArray, DoubleArray> = when (path) {
        is ScanPath.Orbit -> {
            val az = Math.toRadians(path.startAzimuthDeg) + 2 * PI * path.turns * (t / path.durationS)
            val eye = place(az, Math.toRadians(path.elevationDeg), r, 0.0)
            lookAt(eye) to eye
        }
        is ScanPath.WobblyArc -> {
            val u = t / path.durationS
            val az = Math.toRadians(path.centreAzimuthDeg) + Math.toRadians(path.arcDeg) * (u - 0.5)
            val dist = r * (1 + path.sway * wave(0, t))
            val eye = place(az, Math.toRadians(path.elevationDeg), dist, r * path.sway * 0.5 * wave(1, t))
            val amp = Math.toRadians(path.wobbleDeg)
            val yaw = amp * (0.6 * wave(2, t) + 0.4 * wave(3, t))
            val pitch = amp * (0.6 * wave(4, t) + 0.4 * wave(5, t))
            val roll = amp * (0.6 * wave(6, t) + 0.4 * wave(7, t))
            val wobble = D.mul(D.mul(D.axis(1, yaw), D.axis(0, pitch)), D.axis(2, roll))
            D.mul(lookAt(eye), wobble) to eye
        }
    }

    private fun place(az: Double, el: Double, dist: Double, lift: Double): DoubleArray = doubleArrayOf(
        c[0] + dist * cos(el) * sin(az),
        c[1] + dist * sin(el) + lift,
        c[2] + dist * cos(el) * cos(az),
    )

    /** `R_W_Cgl` looking from [eye] at the subject's centre, +Y of W up in the image: columns right, up, back. */
    private fun lookAt(eye: DoubleArray): DoubleArray {
        val f = D.unit(DoubleArray(3) { c[it] - eye[it] })
        val right = D.unit(D.cross(f, doubleArrayOf(0.0, 1.0, 0.0)))
        val up = D.cross(right, f)
        return D.fromColumns(right, up, DoubleArray(3) { -f[it] })
    }
}

/** Double-precision rotation helpers; quaternions are w, x, y, z Hamilton, active (as [Quat]). */
internal object D {
    fun q(v: Quat): DoubleArray = doubleArrayOf(v.w.toDouble(), v.x.toDouble(), v.y.toDouble(), v.z.toDouble())

    fun quat(q: DoubleArray): Quat {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return Quat((q[0] / n).toFloat(), (q[1] / n).toFloat(), (q[2] / n).toFloat(), (q[3] / n).toFloat())
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    fun conj(a: DoubleArray): DoubleArray = doubleArrayOf(a[0], -a[1], -a[2], -a[3])

    fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val r = mul(mul(q, doubleArrayOf(0.0, v[0], v[1], v[2])), conj(q))
        return doubleArrayOf(r[1], r[2], r[3])
    }

    /** Rotation of [angle] rad about axis [a] (0 = X, 1 = Y, 2 = Z). */
    fun axis(a: Int, angle: Double): DoubleArray =
        DoubleArray(4).also { it[0] = cos(angle / 2); it[a + 1] = sin(angle / 2) }

    /** Quaternion of the rotation vector [v] (axis times angle, rad). */
    fun exp(v: DoubleArray): DoubleArray {
        val angle = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val s = if (angle < 1e-12) 0.5 else sin(angle / 2) / angle
        return doubleArrayOf(cos(angle / 2), v[0] * s, v[1] * s, v[2] * s)
    }

    /** Rotation vector of the unit quaternion [q], angle in 0..pi. */
    fun log(q: DoubleArray): DoubleArray {
        val sign = if (q[0] < 0) -1.0 else 1.0
        val w = q[0] * sign
        val n = sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        val angle = 2 * kotlin.math.atan2(n, w)
        val k = if (n < 1e-15) 2.0 / max(w, 1e-300) else angle / n
        return doubleArrayOf(q[1] * k * sign, q[2] * k * sign, q[3] * k * sign)
    }

    /** Angle of the rotation [q], rad, 0..pi. */
    fun angle(q: DoubleArray): Double =
        2 * kotlin.math.atan2(sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), kotlin.math.abs(q[0]))

    fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    fun unit(a: DoubleArray): DoubleArray {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return DoubleArray(3) { a[it] / n }
    }

    /** Quaternion of the rotation matrix with columns [x], [y], [z] (Shepperd). */
    fun fromColumns(x: DoubleArray, y: DoubleArray, z: DoubleArray): DoubleArray {
        val m00 = x[0]; val m10 = x[1]; val m20 = x[2]
        val m01 = y[0]; val m11 = y[1]; val m21 = y[2]
        val m02 = z[0]; val m12 = z[1]; val m22 = z[2]
        val tr = m00 + m11 + m22
        val q = when {
            tr > 0 -> {
                val s = sqrt(tr + 1) * 2
                doubleArrayOf(s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1 + m00 - m11 - m22) * 2
                doubleArrayOf((m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s)
            }
            m11 > m22 -> {
                val s = sqrt(1 + m11 - m00 - m22) * 2
                doubleArrayOf((m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s)
            }
            else -> {
                val s = sqrt(1 + m22 - m00 - m11) * 2
                doubleArrayOf((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4)
            }
        }
        return q
    }
}
