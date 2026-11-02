package com.huntercoles.splatman.splat.math

import kotlin.math.abs
import kotlin.math.tan

/**
 * 4x4 matrix, column-major `FloatArray(16)` as `glUniformMatrix4fv(..., transpose = false)` takes it, acting on
 * column vectors (ADR 0003): element (row r, column c) is `values[c * 4 + r]`, so a translation sits at 12..14.
 * Treat it as immutable: every operation returns a new matrix.
 */
class Mat4(val values: FloatArray) {
    init {
        require(values.size == 16) { "Mat4 needs 16 values, got ${values.size}" }
    }

    operator fun get(row: Int, col: Int): Float = values[col * 4 + row]

    /** `this * o`: applied to a point, `o` acts first. */
    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += this[row, k] * o[k, c]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    /** Transforms a point (w = 1), dropping the last row (affine matrices). */
    fun transformPoint(p: Vec3): Vec3 = Vec3(
        this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z + this[0, 3],
        this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z + this[1, 3],
        this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z + this[2, 3],
    )

    /** Transforms a direction (w = 0). */
    fun transformVector(v: Vec3): Vec3 = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z,
    )

    fun transpose(): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) r[row * 4 + c] = values[c * 4 + row]
        return Mat4(r)
    }

    /** General inverse by cofactors, computed in double; throws if the matrix is singular. */
    fun inverse(): Mat4 {
        val m = DoubleArray(16) { values[it].toDouble() }
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        val scale = (0 until 16).maxOf { abs(m[it]) }
        require(scale > 0.0 && abs(det) > SINGULAR_EPS * scale * scale * scale * scale) {
            "matrix is singular (det = $det)"
        }
        return Mat4(FloatArray(16) { (inv[it] / det).toFloat() })
    }

    /**
     * Inverse of a rigid transform `[R t; 0 1]` (R orthonormal): `[R^T  -R^T t; 0 1]`. Cheaper and better
     * conditioned than [inverse]; the result is wrong for a matrix with scale or shear.
     */
    fun rigidInverse(): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 3) for (row in 0 until 3) r[c * 4 + row] = this[c, row]
        val tx = values[12]; val ty = values[13]; val tz = values[14]
        for (row in 0 until 3) {
            r[12 + row] = -(this[0, row] * tx + this[1, row] * ty + this[2, row] * tz)
        }
        r[15] = 1f
        return Mat4(r)
    }

    override fun equals(other: Any?): Boolean = other is Mat4 && values.contentEquals(other.values)
    override fun hashCode(): Int = values.contentHashCode()
    override fun toString(): String = "Mat4${values.contentToString()}"

    companion object {
        private const val SINGULAR_EPS = 1e-12

        fun identity(): Mat4 = Mat4(FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f })

        fun translation(t: Vec3): Mat4 = fromRotationTranslation(Quat.IDENTITY, t)

        /** `[R(q) t; 0 1]`: rotates by the unit quaternion `q`, then translates by `t` (a `T_a_b`, ADR 0003). */
        fun fromRotationTranslation(q: Quat, t: Vec3): Mat4 {
            val r = q.toMat3()
            val m = FloatArray(16)
            for (c in 0 until 3) for (row in 0 until 3) m[c * 4 + row] = r[row, c]
            m[12] = t.x; m[13] = t.y; m[14] = t.z; m[15] = 1f
            return Mat4(m)
        }

        /**
         * OpenGL perspective projection (camera looks down -Z, NDC z in [-1, 1]), as `android.opengl.Matrix
         * .perspectiveM`. `fovYRad` is the full vertical field of view.
         */
        fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
            require(fovYRad > 0f && aspect > 0f && near > 0f && far > near) {
                "bad perspective: fovY=$fovYRad aspect=$aspect near=$near far=$far"
            }
            val f = 1f / tan(fovYRad / 2f)
            val m = FloatArray(16)
            m[0] = f / aspect
            m[5] = f
            m[10] = (far + near) / (near - far)
            m[11] = -1f
            m[14] = 2f * far * near / (near - far)
            return Mat4(m)
        }

        /**
         * OpenGL view matrix (world -> camera, camera looks down -Z with +Y up), as `android.opengl.Matrix
         * .setLookAtM`. `up` must not be parallel to `center - eye`.
         */
        fun lookAt(eye: Vec3, center: Vec3, up: Vec3): Mat4 {
            val f = (center - eye).normalized()
            val s = (f cross up).normalized()
            val u = s cross f
            val m = FloatArray(16)
            m[0] = s.x; m[4] = s.y; m[8] = s.z
            m[1] = u.x; m[5] = u.y; m[9] = u.z
            m[2] = -f.x; m[6] = -f.y; m[10] = -f.z
            m[12] = -(s dot eye)
            m[13] = -(u dot eye)
            m[14] = f dot eye
            m[15] = 1f
            return Mat4(m)
        }
    }
}
