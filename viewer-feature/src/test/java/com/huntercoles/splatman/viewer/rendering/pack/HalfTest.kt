package com.huntercoles.splatman.viewer.rendering.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class HalfTest {

    @Test
    fun `known values encode exactly`() {
        assertEquals(0x0000, Half.fromFloat(0f))
        assertEquals(0x8000, Half.fromFloat(-0f))
        assertEquals(0x3C00, Half.fromFloat(1f))
        assertEquals(0xC000, Half.fromFloat(-2f))
        assertEquals(0x3555, Half.fromFloat(1f / 3f))
        assertEquals(0x7BFF, Half.fromFloat(65504f))
        assertEquals(0x0400, Half.fromFloat(6.1035156e-5f)) // smallest normal
        assertEquals(0x0001, Half.fromFloat(5.9604645e-8f)) // smallest subnormal
        assertEquals(0x7C00, Half.fromFloat(70000f))
        assertEquals(0x7C00, Half.fromFloat(Float.POSITIVE_INFINITY))
        assertEquals(0x0000, Half.fromFloat(1e-9f))
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
    }

    @Test
    fun `rounds to nearest even`() {
        assertEquals(0x3C00, Half.fromFloat(1f + 1f / 2048f)) // tie, 1.0 is even
        assertEquals(0x3C02, Half.fromFloat(1f + 3f / 2048f)) // tie, rounds up to even
        assertEquals(0x3C01, Half.fromFloat(1f + 1.5f / 2048f))
        assertEquals(0x7C00, Half.fromFloat(65520f)) // rounds past the max to infinity
    }

    @Test
    fun `round trip within half precision`() {
        var v = 1e-4f
        while (v < 6e4f) {
            for (s in floatArrayOf(v, -v)) {
                val back = Half.toFloat(Half.fromFloat(s))
                assertTrue(abs(back - s) <= abs(s) * 4.9e-4f, "$s -> $back")
            }
            v *= 1.0137f
        }
        assertEquals(-0f, Half.toFloat(0x8000))
        assertEquals(5.9604645e-8f, Half.toFloat(0x0001))
        assertEquals(Float.NEGATIVE_INFINITY, Half.toFloat(0xFC00))
    }
}
