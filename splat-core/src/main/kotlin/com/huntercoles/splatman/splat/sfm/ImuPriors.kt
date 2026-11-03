package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gyro priors for SfM matching (epic #200, ADR 0012). Rates are body rates in the IMU frame; `rotationCcvImu`
 * (`R_Ccv_Imu`) takes them into the OpenCV camera frame Ccv. With `R_W_C(t1) = R_W_C(t0) exp(w dt)` the relative
 * rotation returned is `R_Ccv(t1)_Ccv(t0)`. Integration runs in Double.
 */
object ImuPriors {

    /** The largest gap between gyro samples the integration bridges. */
    const val MAX_GAP_NS = 50_000_000L

    /** Pairs whose predicted rotation exceeds this are dropped: too little overlap to match. */
    const val MAX_PAIR_ROTATION_RAD = PI_OVER_3

    /**
     * `R_Ccv(t1)_Ccv(t0)` from [gyro] between [t0Ns] and [t1Ns] (`t0Ns <= t1Ns`): the rate is linearly interpolated
     * at both ends, each step between samples uses the rate at its midpoint. Throws [IllegalArgumentException] when
     * the samples do not cover the interval or two samples within it are more than 50 ms apart.
     */
    fun relativeRotation(gyro: ImuSamples, rotationCcvImu: Quat, t0Ns: Long, t1Ns: Long): Quat =
        integrate(gyro, rotationCcvImu, t0Ns, t1Ns).conjugate().toQuat()

    /**
     * Every pair `(i, j)` of positions in [frames] with `0 < j - i <= options.matchWindow`, ordered by i then j.
     * With `options.useGyroPrior` each carries `R_Cj_Ci` from [gyro] and pairs turned by more than 60 degrees are
     * dropped; without it the rotation is null and the gyro is not read.
     */
    fun pairs(frames: List<SfmFrame>, gyro: ImuSamples, rotationCcvImu: Quat, options: SfmOptions): List<FramePair> {
        // R_C0_Ck for each frame k, chained frame to frame
        val orientations: List<DQuat>? = if (!options.useGyroPrior) {
            null
        } else {
            val out = ArrayList<DQuat>(frames.size)
            frames.forEachIndexed { k, f ->
                out += if (k == 0) {
                    DQuat.IDENTITY
                } else {
                    out[k - 1] * integrate(gyro, rotationCcvImu, frames[k - 1].timestampNs, f.timestampNs)
                }
            }
            out
        }
        val result = mutableListOf<FramePair>()
        for (i in frames.indices) {
            for (j in i + 1..minOf(frames.lastIndex, i + options.matchWindow)) {
                if (orientations == null) {
                    result += FramePair(i, j, null)
                    continue
                }
                val rCjCi = orientations[j].conjugate() * orientations[i]
                if (rCjCi.angle() > MAX_PAIR_ROTATION_RAD) continue
                result += FramePair(i, j, rCjCi.toQuat())
            }
        }
        return result
    }

    /** `R_C0_C1` (C1 seen from C0) over [t0Ns, t1Ns]. */
    private fun integrate(gyro: ImuSamples, rotationCcvImu: Quat, t0Ns: Long, t1Ns: Long): DQuat {
        require(t0Ns <= t1Ns) { "t0 must not be after t1, got $t0Ns > $t1Ns" }
        val ts = gyro.timestampsNs
        val n = ts.size
        require(n > 0 && ts[0] <= t0Ns && t1Ns <= ts[n - 1]) {
            "gyro samples ${if (n == 0) "(none)" else "${ts[0]}..${ts[n - 1]}"} do not cover $t0Ns..$t1Ns"
        }
        if (t0Ns == t1Ns) return DQuat.IDENTITY
        val r = DQuat.of(rotationCcvImu.normalized())

        // a: last sample at or before t0; b: first sample at or after t1
        var a = 0
        while (a + 1 < n && ts[a + 1] <= t0Ns) a++
        var b = a
        while (ts[b] < t1Ns) b++
        for (k in a until b) {
            require(ts[k + 1] >= ts[k]) { "gyro timestamps out of order at sample ${k + 1}" }
            require(ts[k + 1] - ts[k] <= MAX_GAP_NS) {
                "gyro gap of ${(ts[k + 1] - ts[k]) / 1_000_000.0} ms between ${ts[k]} and ${ts[k + 1]} exceeds 50 ms"
            }
        }

        fun sample(k: Int) = r.rotate(
            gyro.xyz[3 * k].toDouble(), gyro.xyz[3 * k + 1].toDouble(), gyro.xyz[3 * k + 2].toDouble(),
        )
        fun rateAt(lo: Int, t: Long): DoubleArray {
            if (lo + 1 >= n || ts[lo + 1] == ts[lo]) return sample(lo)
            val u = (t - ts[lo]).toDouble() / (ts[lo + 1] - ts[lo])
            val p = sample(lo)
            val q = sample(lo + 1)
            return DoubleArray(3) { p[it] + u * (q[it] - p[it]) }
        }

        var q = DQuat.IDENTITY
        var prevT = t0Ns
        var prevRate = rateAt(a, t0Ns)
        for (k in a + 1..b) {
            val t = if (k == b) t1Ns else ts[k]
            val rate = if (k == b) rateAt(b - 1, t1Ns) else sample(k)
            val dt = (t - prevT) / 1e9
            if (dt > 0.0) {
                q = q * DQuat.exp(
                    0.5 * (prevRate[0] + rate[0]) * dt,
                    0.5 * (prevRate[1] + rate[1]) * dt,
                    0.5 * (prevRate[2] + rate[2]) * dt,
                )
            }
            prevT = t
            prevRate = rate
        }
        return q
    }

    /** Hamilton quaternion in Double, private to the integration. */
    private class DQuat(val w: Double, val x: Double, val y: Double, val z: Double) {
        operator fun times(o: DQuat) = DQuat(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
        )

        fun conjugate() = DQuat(w, -x, -y, -z)

        fun rotate(vx: Double, vy: Double, vz: Double): DoubleArray {
            val p = this * DQuat(0.0, vx, vy, vz) * conjugate()
            return doubleArrayOf(p.x, p.y, p.z)
        }

        fun angle(): Double = 2.0 * atan2(sqrt(x * x + y * y + z * z), abs(w))

        fun toQuat(): Quat {
            val nrm = sqrt(w * w + x * x + y * y + z * z)
            return Quat((w / nrm).toFloat(), (x / nrm).toFloat(), (y / nrm).toFloat(), (z / nrm).toFloat())
        }

        companion object {
            val IDENTITY = DQuat(1.0, 0.0, 0.0, 0.0)

            fun of(q: Quat) = DQuat(q.w.toDouble(), q.x.toDouble(), q.y.toDouble(), q.z.toDouble())

            /** exp of the rotation vector (rx, ry, rz), radians. */
            fun exp(rx: Double, ry: Double, rz: Double): DQuat {
                val theta = sqrt(rx * rx + ry * ry + rz * rz)
                if (theta < 1e-12) return DQuat(1.0, 0.5 * rx, 0.5 * ry, 0.5 * rz)
                val s = sin(0.5 * theta) / theta
                return DQuat(cos(0.5 * theta), rx * s, ry * s, rz * s)
            }
        }
    }
}

private const val PI_OVER_3 = kotlin.math.PI / 3
