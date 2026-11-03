package com.huntercoles.splatman.splat.sfm

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

const val MS = 1_000_000L

/** Angle in radians of the rotation taking [a] to [b]; `q` and `-q` count as the same rotation. Double maths. */
fun quatAngle(a: Quat, b: Quat): Double {
    val d = a.conjugate() * b
    val v = sqrt(d.x.toDouble() * d.x + d.y.toDouble() * d.y + d.z.toDouble() * d.z)
    return 2.0 * atan2(v, abs(d.w.toDouble()))
}

/** Angle in radians between two non-zero vectors, Double maths. */
fun vecAngle(a: Vec3, b: Vec3): Double {
    val cx = a.y.toDouble() * b.z - a.z.toDouble() * b.y
    val cy = a.z.toDouble() * b.x - a.x.toDouble() * b.z
    val cz = a.x.toDouble() * b.y - a.y.toDouble() * b.x
    val dot = a.x.toDouble() * b.x + a.y.toDouble() * b.y + a.z.toDouble() * b.z
    return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot)
}

/** IMU samples (IMU frame) at [timesNs], the value at each time given by [value]. */
fun imuAt(timesNs: List<Long>, value: (Long) -> Vec3): ImuSamples {
    val xyz = FloatArray(3 * timesNs.size)
    timesNs.forEachIndexed { k, t ->
        val r = value(t)
        xyz[3 * k] = r.x
        xyz[3 * k + 1] = r.y
        xyz[3 * k + 2] = r.z
    }
    return ImuSamples(timesNs.toLongArray(), xyz)
}

/** Sample times from [fromNs] to [toNs] inclusive every [stepNs]. */
fun times(fromNs: Long, toNs: Long, stepNs: Long): List<Long> = (fromNs..toNs step stepNs).toList()
