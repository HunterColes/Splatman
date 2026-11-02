package com.huntercoles.splatman.splat.math

/**
 * 3x3 matrix, column-major `FloatArray(9)` like [Mat4]: element (row r, column c) is `values[c * 3 + r]`.
 * Treat it as immutable: every operation returns a new matrix.
 */
class Mat3(val values: FloatArray) {
    init {
        require(values.size == 9) { "Mat3 needs 9 values, got ${values.size}" }
    }

    operator fun get(row: Int, col: Int): Float = values[col * 3 + row]

    operator fun times(o: Mat3): Mat3 {
        val r = FloatArray(9)
        for (c in 0 until 3) for (row in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += this[row, k] * o[k, c]
            r[c * 3 + row] = s
        }
        return Mat3(r)
    }

    operator fun times(v: Vec3): Vec3 = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z,
    )

    fun transpose(): Mat3 = fromRows(
        this[0, 0], this[1, 0], this[2, 0],
        this[0, 1], this[1, 1], this[2, 1],
        this[0, 2], this[1, 2], this[2, 2],
    )

    override fun equals(other: Any?): Boolean = other is Mat3 && values.contentEquals(other.values)
    override fun hashCode(): Int = values.contentHashCode()
    override fun toString(): String = "Mat3${values.contentToString()}"

    companion object {
        fun identity(): Mat3 = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /** Builds from row-major arguments (reads like the maths); stores column-major. */
        @Suppress("LongParameterList")
        fun fromRows(
            m00: Float, m01: Float, m02: Float,
            m10: Float, m11: Float, m12: Float,
            m20: Float, m21: Float, m22: Float,
        ): Mat3 = Mat3(floatArrayOf(m00, m10, m20, m01, m11, m21, m02, m12, m22))
    }
}
