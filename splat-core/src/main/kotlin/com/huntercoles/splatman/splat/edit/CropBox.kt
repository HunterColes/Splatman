package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** A face of a [CropBox], named by its local axis and outward side. The order is fixed (ADR 0011). */
enum class BoxFace { X_NEG, X_POS, Y_NEG, Y_POS, Z_NEG, Z_POS }

/**
 * An oriented box in the world frame W (ADR 0003, +Y up, metres; ADR 0011): [centre], per-axis [halfExtents] in the
 * box's local axes, and [rotation] local -> W. A point p is inside when |axis_k . (p - centre)| <= halfExtents_k for
 * k = x, y, z (boundary inclusive; NaN is never inside). The rotation is used normalised; a zero-length or
 * non-finite one is rejected.
 */
data class CropBox(val centre: Vec3, val halfExtents: Vec3, val rotation: Quat) {

    /** Local axes in W, row-major: x axis (0..2), y axis (3..5), z axis (6..8). */
    internal val axes: FloatArray

    init {
        val n = rotation.norm()
        require(n > 0f && n.isFinite()) { "CropBox rotation must be a non-zero finite quaternion, got $rotation" }
        val r = rotation.normalized()
        val ax = r.rotate(Vec3.UNIT_X)
        val ay = r.rotate(Vec3.UNIT_Y)
        val az = r.rotate(Vec3.UNIT_Z)
        axes = floatArrayOf(ax.x, ax.y, ax.z, ay.x, ay.y, ay.z, az.x, az.y, az.z)
    }

    /** True when [p] lies inside the box or on its boundary. */
    fun contains(p: Vec3): Boolean = inside(p.x, p.y, p.z)

    internal fun inside(px: Float, py: Float, pz: Float): Boolean {
        val dx = px - centre.x
        val dy = py - centre.y
        val dz = pz - centre.z
        val a = axes
        return abs(a[0] * dx + a[1] * dy + a[2] * dz) <= halfExtents.x &&
            abs(a[3] * dx + a[4] * dy + a[5] * dz) <= halfExtents.y &&
            abs(a[6] * dx + a[7] * dy + a[8] * dz) <= halfExtents.z
    }

    /**
     * A new box with [face] moved [deltaMetres] along its outward normal (positive = out), the opposite face fixed;
     * the extent on that axis is clamped to at least [MIN_EXTENT] (the moved face stops 1 cm from the opposite one).
     */
    fun moveFace(face: BoxFace, deltaMetres: Float): CropBox {
        require(deltaMetres.isFinite()) { "deltaMetres must be finite, got $deltaMetres" }
        val axis = face.ordinal / 2
        val positive = face.ordinal % 2 == 1
        val h = when (axis) {
            0 -> halfExtents.x
            1 -> halfExtents.y
            else -> halfExtents.z
        }.toDouble()
        var lo = -h
        var hi = h
        if (positive) hi += deltaMetres else lo -= deltaMetres
        if (hi - lo < MIN_EXTENT) {
            if (positive) hi = lo + MIN_EXTENT else lo = hi - MIN_EXTENT
        }
        val shift = ((lo + hi) / 2).toFloat()
        val half = ((hi - lo) / 2).toFloat()
        val dir = Vec3(axes[3 * axis], axes[3 * axis + 1], axes[3 * axis + 2])
        val newHalf = when (axis) {
            0 -> halfExtents.copy(x = half)
            1 -> halfExtents.copy(y = half)
            else -> halfExtents.copy(z = half)
        }
        return CropBox(centre + dir * shift, newHalf, rotation)
    }

    companion object {
        /** Smallest full extent of a box on any axis, metres (1 cm). */
        const val MIN_EXTENT = 0.01

        /**
         * The W-aligned box over the central [fraction] of splat centres per axis: from the (1 - fraction) / 2 to the
         * (1 + fraction) / 2 quantile (linear interpolation between sorted values), so 0.9 is the 5th..95th
         * percentile. Non-finite coordinates are ignored. Each axis is at least [MIN_EXTENT] wide, centred on the
         * span. Rejects an empty cloud.
         */
        fun fitCentral(cloud: SplatCloud, fraction: Float = 0.9f): CropBox {
            require(cloud.count > 0) { "fitCentral needs at least one splat" }
            require(fraction > 0f && fraction <= 1f) { "fraction must be in (0, 1], got $fraction" }
            val qLo = (1.0 - fraction) / 2
            val qHi = (1.0 + fraction) / 2
            val c = DoubleArray(3)
            val h = DoubleArray(3)
            val values = FloatArray(cloud.count)
            for (axis in 0 until 3) {
                var n = 0
                for (i in 0 until cloud.count) {
                    val v = cloud.positions[3 * i + axis]
                    if (v.isFinite()) values[n++] = v
                }
                require(n > 0) { "fitCentral needs a finite centre on every axis" }
                values.sort(0, n)
                val lo = quantile(values, n, qLo)
                val hi = quantile(values, n, qHi)
                c[axis] = (lo + hi) / 2
                h[axis] = max((hi - lo) / 2, MIN_EXTENT / 2)
            }
            return CropBox(
                Vec3(c[0].toFloat(), c[1].toFloat(), c[2].toFloat()),
                Vec3(h[0].toFloat(), h[1].toFloat(), h[2].toFloat()),
                Quat.IDENTITY,
            )
        }

        private fun quantile(sorted: FloatArray, n: Int, q: Double): Double {
            val pos = q * (n - 1)
            val i = floor(pos).toInt().coerceIn(0, n - 1)
            val j = (i + 1).coerceAtMost(n - 1)
            val t = pos - i
            return sorted[i] + (sorted[j].toDouble() - sorted[i]) * t
        }
    }
}
