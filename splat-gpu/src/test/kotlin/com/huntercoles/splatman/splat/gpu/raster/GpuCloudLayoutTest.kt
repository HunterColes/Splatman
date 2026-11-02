package com.huntercoles.splatman.splat.gpu.raster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GpuCloudLayoutTest {
    @Test
    fun `sections follow SplatCloud's arrays back to back`() {
        val c = 100
        assertEquals(0, CloudLayout.floatOffset(CloudLayout.POSITIONS, c))
        assertEquals(300, CloudLayout.floatOffset(CloudLayout.LOG_SCALES, c))
        assertEquals(600, CloudLayout.floatOffset(CloudLayout.ROTATIONS, c))
        assertEquals(1000, CloudLayout.floatOffset(CloudLayout.OPACITY_LOGITS, c))
        assertEquals(1100, CloudLayout.floatOffset(CloudLayout.SH_DC, c))
        assertEquals(4400, CloudLayout.byteOffset(CloudLayout.SH_DC, c))
        // shDc (3 floats a splat) ends the buffer exactly.
        assertEquals(CloudLayout.FLOATS_PER_SPLAT, CloudLayout.SH_DC + 3)
        assertEquals(4 * 14 * c, CloudLayout.bytes(c))
    }

    @Test
    fun `an empty cloud still gets a buffer`() {
        assertEquals(4 * 14, CloudLayout.bytes(0))
        assertThrows<IllegalArgumentException> { CloudLayout.bytes(-1) }
        assertThrows<ArithmeticException> { CloudLayout.bytes(Int.MAX_VALUE / 14) }
    }

    @Test
    fun `capacity grows only when needed, at least doubling`() {
        assertEquals(10, Capacity.grow(10, 0))
        assertEquals(10, Capacity.grow(10, 10))
        assertEquals(20, Capacity.grow(10, 11))
        assertEquals(500, Capacity.grow(10, 500))
        assertEquals(2, Capacity.grow(1, 2))
        assertEquals(Int.MAX_VALUE, Capacity.grow(Int.MAX_VALUE / 2 + 1, Int.MAX_VALUE / 2 + 2))
        assertThrows<IllegalArgumentException> { Capacity.grow(-1, 3) }
    }

    @Test
    fun `a run of growing and shrinking sizes reallocates only a few times`() {
        var cap = 1
        var reallocs = 0
        for (n in listOf(50, 2000, 1999, 50, 2000, 3000, 2500, 3000)) {
            val next = Capacity.grow(cap, n)
            if (next != cap) reallocs++
            cap = next
        }
        assertEquals(3, reallocs)
        assertEquals(4000, cap)
    }
}
