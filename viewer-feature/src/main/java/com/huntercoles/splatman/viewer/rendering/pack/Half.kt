package com.huntercoles.splatman.viewer.rendering.pack

/**
 * IEEE 754 binary16 conversions (Java 17 has no `Float.floatToFloat16`). Round to nearest even, subnormals kept,
 * overflow to infinity, NaN stays NaN.
 */
object Half {
    private const val F32_EXP_BIAS = 127
    private const val F16_EXP_BIAS = 15
    private const val F16_MAX_EXP = 31
    private const val SUBNORMAL_UNIT = 5.9604645e-8f // 2^-24

    /** The binary16 bits of [value] in the low 16 bits of the result. */
    fun fromFloat(value: Float): Int {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        if (exp == 0xFF) return sign or 0x7C00 or (if (mant != 0) 0x200 else 0)
        val e = exp - F32_EXP_BIAS + F16_EXP_BIAS
        if (e >= F16_MAX_EXP) return sign or 0x7C00
        if (e <= 0) {
            if (e < -10) return sign
            // Subnormal: the mantissa with its implicit 1, shifted right by 14 - e bits.
            return sign or roundShift(mant or 0x800000, 14 - e)
        }
        // Normal: round the 23-bit mantissa to 10 bits; a carry rolls into the exponent (up to infinity), as IEEE.
        return sign or roundShift((e shl 23) or mant, 13)
    }

    /** The float of the binary16 bits in the low 16 bits of [half]. */
    fun toFloat(half: Int): Float {
        val sign = (half and 0x8000) shl 16
        val exp = (half ushr 10) and 0x1F
        val mant = half and 0x3FF
        return when (exp) {
            0 -> {
                val f = mant * SUBNORMAL_UNIT
                if (sign != 0) -f else f
            }
            F16_MAX_EXP -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(
                sign or ((exp - F16_EXP_BIAS + F32_EXP_BIAS) shl 23) or (mant shl 13),
            )
        }
    }

    /** `value >> shift`, rounded to nearest, ties to even. */
    private fun roundShift(value: Int, shift: Int): Int {
        val halfway = 1 shl (shift - 1)
        val rest = value and ((1 shl shift) - 1)
        var r = value ushr shift
        if (rest > halfway || (rest == halfway && (r and 1) == 1)) r++
        return r
    }
}
