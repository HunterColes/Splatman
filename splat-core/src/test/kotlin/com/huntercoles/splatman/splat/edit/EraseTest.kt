package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #328: keepUnmarked drops every marked splat (states 1 and 2), mirroring cropToBox (ADR 0014 rules 3, 7). */
class EraseTest {

    /** Every per-splat field of [out] slot [o] equals input splat [i] of [src]. */
    private fun assertSplat(src: SplatCloud, i: Int, out: SplatCloud, o: Int) {
        for (a in 0 until 3) assertEquals(src.positions[3 * i + a], out.positions[3 * o + a], "position $i -> $o")
        for (a in 0 until 3) assertEquals(src.logScales[3 * i + a], out.logScales[3 * o + a], "logScale $i -> $o")
        for (a in 0 until 4) assertEquals(src.rotations[4 * i + a], out.rotations[4 * o + a], "rotation $i -> $o")
        assertEquals(src.opacityLogits[i], out.opacityLogits[o], "opacity $i -> $o")
        for (a in 0 until 3) assertEquals(src.shDc[3 * i + a], out.shDc[3 * o + a], "shDc $i -> $o")
        val k = src.shRestPerChannel
        for (c in 0 until 3) for (j in 0 until k) {
            assertEquals(src.shRest[src.shRestIndex(i, c, j)], out.shRest[out.shRestIndex(o, c, j)], "shRest $i -> $o")
        }
    }

    private fun six(shDegree: Int) = cloudAt(FloatArray(18) { it * 0.5f - 4f }, shDegree)

    /** Splat 1 marked by an earlier stroke, splat 4 by the last stroke. */
    private fun maskOneAndFour(): EraseMask = EraseMask(6).apply {
        mark(1)
        beginStroke()
        mark(4)
    }

    private fun keepsInOrder(shDegree: Int) {
        val src = six(shDegree)
        val out = keepUnmarked(src, maskOneAndFour())
        assertEquals(4, out.count)
        assertEquals(shDegree, out.shDegree)
        assertEquals(4 * 3 * SplatCloud.restCoefficients(shDegree), out.shRest.size)
        listOf(0, 2, 3, 5).forEachIndexed { o, i -> assertSplat(src, i, out, o) }
    }

    @Test
    fun `A3 keeps the unmarked splats in order with every field at SH degree 0`() = keepsInOrder(0)

    @Test
    fun `A3 keeps the unmarked splats in order with every field at SH degree 3`() = keepsInOrder(3)

    @Test
    fun `A3 an empty mask returns an equal cloud`() {
        val src = six(3)
        val out = keepUnmarked(src, EraseMask(6))
        assertEquals(src.count, out.count)
        assertEquals(src.shDegree, out.shDegree)
        assertArrayEquals(src.positions, out.positions)
        assertArrayEquals(src.logScales, out.logScales)
        assertArrayEquals(src.rotations, out.rotations)
        assertArrayEquals(src.opacityLogits, out.opacityLogits)
        assertArrayEquals(src.shDc, out.shDc)
        assertArrayEquals(src.shRest, out.shRest)
    }

    @Test
    fun `A3 a full mask returns count 0`() {
        val src = six(3)
        val mask = EraseMask(6)
        for (i in 0 until 6) mask.mark(i)
        val out = keepUnmarked(src, mask)
        assertEquals(0, out.count)
        assertEquals(3, out.shDegree)
        assertEquals(0, out.positions.size)
        assertEquals(0, out.shRest.size)
    }

    @Test
    fun `A3 the input cloud is untouched`() {
        val src = six(0)
        val before = src.positions.copyOf()
        keepUnmarked(src, maskOneAndFour())
        assertEquals(6, src.count)
        assertArrayEquals(before, src.positions)
    }
}
