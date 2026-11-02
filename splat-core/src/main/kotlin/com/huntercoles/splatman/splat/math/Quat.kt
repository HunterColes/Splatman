package com.huntercoles.splatman.splat.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hamilton quaternion (`i*j = k`), stored w, x, y, z (ADR 0002, 0003). As a rotation it is active and maps local
 * coordinates to the parent frame; unit length when it is a rotation.
 */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    /** Hamilton product `this * o`: applying the result rotates by `o` first, then by `this`. */
    operator fun times(o: Quat): Quat = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate(): Quat = Quat(w, -x, -y, -z)

    fun normSquared(): Float = w * w + x * x + y * y + z * z
    fun norm(): Float = sqrt(normSquared())

    /** Unit quaternion in the same direction; throws on the zero quaternion. */
    fun normalized(): Quat {
        val n = norm()
        require(n > 0f && n.isFinite()) { "cannot normalize a zero or non-finite quaternion: $this" }
        return Quat(w / n, x / n, y / n, z / n)
    }

    /** Rotates `v` by this unit quaternion: `q v q*`, expanded as `v + 2w (u x v) + 2 u x (u x v)`. */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    /** Rotation matrix of this unit quaternion. */
    fun toMat3(): Mat3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat3.fromRows(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        /** Rotation of `angleRad` about `axis` (right-hand rule); the axis need not be unit length. */
        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quat {
            val a = axis.normalized()
            val half = angleRad * 0.5f
            val s = sin(half)
            return Quat(cos(half), a.x * s, a.y * s, a.z * s)
        }
    }
}
