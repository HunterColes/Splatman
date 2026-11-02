package com.huntercoles.splatman.splat.math

import kotlin.math.sqrt

/** A 3-vector (metres when it is a position, ADR 0002). Immutable. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float): Vec3 = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    /** Right-handed cross product: X cross Y = Z. */
    infix fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun lengthSquared(): Float = dot(this)
    fun length(): Float = sqrt(lengthSquared())

    /** Unit vector in the same direction; throws on the zero vector. */
    fun normalized(): Vec3 {
        val len = length()
        require(len > 0f && len.isFinite()) { "cannot normalize a zero or non-finite vector: $this" }
        return this / len
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UNIT_X = Vec3(1f, 0f, 0f)
        val UNIT_Y = Vec3(0f, 1f, 0f)
        val UNIT_Z = Vec3(0f, 0f, 1f)
    }
}

operator fun Float.times(v: Vec3): Vec3 = v * this
