package com.huntercoles.splatman.splat.gpu

import com.huntercoles.splatman.splat.gpu.Std430.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Std430LayoutTest {
    @Test
    fun `array strides follow std430, not std140`() {
        assertEquals(4, Std430Type.FLOAT.arrayStride)
        assertEquals(4, Std430Type.UINT.arrayStride)
        assertEquals(8, Std430Type.VEC2.arrayStride)
        assertEquals(16, Std430Type.VEC3.arrayStride)
        assertEquals(16, Std430Type.VEC4.arrayStride)
    }

    @Test
    fun `array bytes are stride times count`() {
        assertEquals(0, Std430.arrayBytes(Std430Type.FLOAT, 0))
        assertEquals(4 shl 20, Std430.arrayBytes(Std430Type.FLOAT, 1 shl 20))
        assertEquals(160, Std430.arrayBytes(Std430Type.VEC3, 10))
        assertEquals(80, Std430.arrayBytes(Std430Type.VEC2, 10))
    }

    @Test
    fun `array bytes overflow throws instead of wrapping`() {
        assertThrows<ArithmeticException> { Std430.arrayBytes(Std430Type.VEC4, Int.MAX_VALUE / 8) }
        assertThrows<IllegalArgumentException> { Std430.arrayBytes(Std430Type.FLOAT, -1) }
    }

    @Test
    fun `vec3 aligns to 16 and a scalar packs into its tail`() {
        val s = Std430.struct(
            Member("a", Std430Type.FLOAT),
            Member("p", Std430Type.VEC3),
            Member("b", Std430Type.FLOAT),
        )
        assertEquals(mapOf("a" to 0, "p" to 16, "b" to 28), s.offsets)
        assertEquals(16, s.align)
        assertEquals(32, s.size)
    }

    @Test
    fun `scalar-only struct keeps 4-byte alignment`() {
        val s = Std430.struct(Member("x", Std430Type.FLOAT), Member("i", Std430Type.UINT), Member("y", Std430Type.FLOAT))
        assertEquals(mapOf("x" to 0, "i" to 4, "y" to 8), s.offsets)
        assertEquals(4, s.align)
        assertEquals(12, s.size)
    }

    @Test
    fun `scalar arrays in a struct are tightly packed and the size rounds to the struct alignment`() {
        // SplatGrads.entryGrads: 9 floats per tile entry.
        val entry = Std430.struct(Member("g", Std430Type.FLOAT, 9))
        assertEquals(36, entry.size)
        val s = Std430.struct(Member("uv", Std430Type.VEC2), Member("k", Std430Type.FLOAT, 3))
        assertEquals(mapOf("uv" to 0, "k" to 8), s.offsets)
        assertEquals(8, s.align)
        assertEquals(24, s.size)
    }

    @Test
    fun `vec3 arrays in a struct use a 16-byte stride`() {
        val s = Std430.struct(Member("n", Std430Type.UINT), Member("v", Std430Type.VEC3, 2))
        assertEquals(mapOf("n" to 0, "v" to 16), s.offsets)
        assertEquals(48, s.size)
    }

    @Test
    fun `bad structs are rejected`() {
        assertThrows<IllegalArgumentException> { Std430.struct() }
        assertThrows<IllegalArgumentException> {
            Std430.struct(Member("a", Std430Type.FLOAT), Member("a", Std430Type.INT))
        }
        assertThrows<IllegalArgumentException> { Std430.struct(Member("a", Std430Type.FLOAT, 0)) }
    }
}
