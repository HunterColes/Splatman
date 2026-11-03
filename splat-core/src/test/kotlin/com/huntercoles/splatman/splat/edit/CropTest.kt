package com.huntercoles.splatman.splat.edit

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.random.Random

/** #182: cropToBox keeps the splats whose centres lie inside a CropBox (ADR 0011). */
class CropTest {

    private val unitBox = CropBox(Vec3.ZERO, Vec3(1f, 1f, 1f), Quat.IDENTITY)

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

    /** Splats 0, 2, 4 inside the unit box, 1 and 3 outside, interleaved. */
    private fun interleaved(shDegree: Int) = cloudAt(
        floatArrayOf(
            0.1f, 0.2f, 0.3f,
            5f, 0f, 0f,
            -0.9f, 0.9f, -0.5f,
            0f, -3f, 0f,
            0.5f, -0.5f, 0.99f,
        ),
        shDegree,
    )

    @Test
    fun `A1 box at the origin keeps exactly the inside splats`() {
        val src = interleaved(0)
        val out = cropToBox(src, unitBox)
        assertEquals(3, out.count)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.3f, -0.9f, 0.9f, -0.5f, 0.5f, -0.5f, 0.99f),
            out.positions,
        )
    }

    @Test
    fun `A1 order kept and every field carried at SH degree 0`() {
        val src = interleaved(0)
        val out = cropToBox(src, unitBox)
        assertEquals(0, out.shDegree)
        assertEquals(3, out.count)
        listOf(0, 2, 4).forEachIndexed { o, i -> assertSplat(src, i, out, o) }
    }

    @Test
    fun `A1 order kept and every field carried at SH degree 3`() {
        val src = interleaved(3)
        val out = cropToBox(src, unitBox)
        assertEquals(3, out.shDegree)
        assertEquals(3 * 3 * 15, out.shRest.size)
        listOf(0, 2, 4).forEachIndexed { o, i -> assertSplat(src, i, out, o) }
    }

    @Test
    fun `A1 rotated box 45 degrees about Y keeps the splat only the rotation covers`() {
        // Slab half (1, 1, 0.1) rotated +45 deg about Y: (0.6, 0, -0.6) is inside, (0.9, 0, 0) is not (CropBoxTest).
        val src = cloudAt(floatArrayOf(0.9f, 0f, 0f, 0.6f, 0f, -0.6f), 1)
        val box = CropBox(Vec3.ZERO, Vec3(1f, 1f, 0.1f), Quat.fromAxisAngle(Vec3.UNIT_Y, (PI / 4).toFloat()))
        val out = cropToBox(src, box)
        assertEquals(1, out.count)
        assertSplat(src, 1, out, 0)
    }

    @Test
    fun `A1 empty result is a valid cloud of count 0`() {
        val src = interleaved(2)
        val out = cropToBox(src, CropBox(Vec3(100f, 100f, 100f), Vec3(1f, 1f, 1f), Quat.IDENTITY))
        assertEquals(0, out.count)
        assertEquals(2, out.shDegree)
        assertEquals(0, out.positions.size)
        assertEquals(0, out.logScales.size)
        assertEquals(0, out.rotations.size)
        assertEquals(0, out.opacityLogits.size)
        assertEquals(0, out.shDc.size)
        assertEquals(0, out.shRest.size)
    }

    @Test
    fun `A1 all inside gives equal arrays in new copies`() {
        val src = interleaved(3)
        val out = cropToBox(src, CropBox(Vec3.ZERO, Vec3(10f, 10f, 10f), Quat.IDENTITY))
        assertEquals(src.count, out.count)
        assertEquals(src.shDegree, out.shDegree)
        assertArrayEquals(src.positions, out.positions)
        assertArrayEquals(src.logScales, out.logScales)
        assertArrayEquals(src.rotations, out.rotations)
        assertArrayEquals(src.opacityLogits, out.opacityLogits)
        assertArrayEquals(src.shDc, out.shDc)
        assertArrayEquals(src.shRest, out.shRest)
        assertNotSame(src.positions, out.positions)
        assertNotSame(src.logScales, out.logScales)
        assertNotSame(src.rotations, out.rotations)
        assertNotSame(src.opacityLogits, out.opacityLogits)
        assertNotSame(src.shDc, out.shDc)
        assertNotSame(src.shRest, out.shRest)
    }

    @Test
    fun `A1 boundary is inclusive`() {
        val src = cloudAt(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, -1f, Math.nextUp(1f), 0f, 0f))
        val out = cropToBox(src, unitBox)
        assertEquals(3, out.count)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, -1f), out.positions)
    }

    @Test
    fun `A1 input is untouched`() {
        val src = interleaved(3)
        val before = listOf(src.positions, src.logScales, src.rotations, src.opacityLogits, src.shDc, src.shRest)
            .map { it.copyOf() }
        cropToBox(src, unitBox)
        val after = listOf(src.positions, src.logScales, src.rotations, src.opacityLogits, src.shDc, src.shRest)
        before.zip(after).forEach { (b, a) -> assertArrayEquals(b, a) }
        assertEquals(5, src.count)
        assertEquals(3, src.shDegree)
    }

    @Test
    fun `A1 same cloud and box give identical output`() {
        val rnd = Random(42)
        val src = cloudAt(FloatArray(3 * 1000) { rnd.nextFloat() * 4f - 2f }, 3)
        val a = cropToBox(src, unitBox)
        val b = cropToBox(src, unitBox)
        assertEquals(a.count, b.count)
        assertTrue(a.count in 1 until 1000)
        assertArrayEquals(a.positions, b.positions)
        assertArrayEquals(a.logScales, b.logScales)
        assertArrayEquals(a.rotations, b.rotations)
        assertArrayEquals(a.opacityLogits, b.opacityLogits)
        assertArrayEquals(a.shDc, b.shDc)
        assertArrayEquals(a.shRest, b.shRest)
    }

    @Test
    fun `H1 NaN centres are dropped`() {
        val nan = Float.NaN
        val src = cloudAt(floatArrayOf(nan, 0f, 0f, 0.5f, 0.5f, 0.5f, 0f, nan, 0f, 0f, 0f, nan, nan, nan, nan))
        val out = cropToBox(src, CropBox(Vec3.ZERO, Vec3(1e30f, 1e30f, 1e30f), Quat.IDENTITY))
        assertEquals(1, out.count)
        assertSplat(src, 1, out, 0)
    }

    @Test
    fun `A4 1M splats at SH degree 3 crop in under 1 s`() {
        val n = 1_000_000
        val rnd = Random(42)
        val src = cloudAt(FloatArray(3 * n) { rnd.nextFloat() * 2f - 1f }, 3)
        // z in [-0.5, 0.5] keeps about half; the expected count is computed here, not by the code under test.
        val box = CropBox(Vec3.ZERO, Vec3(1f, 1f, 0.5f), Quat.IDENTITY)
        var expected = 0
        for (i in 0 until n) {
            val z = src.positions[3 * i + 2]
            if (z >= -0.5f && z <= 0.5f) expected++
        }
        cropToBox(src, box) // one warm-up run for the JIT
        val t0 = System.nanoTime()
        val out = cropToBox(src, box)
        val seconds = (System.nanoTime() - t0) / 1e9
        assertEquals(expected, out.count)
        assertTrue(seconds < 1.0, "1M splats at degree 3 took $seconds s")
    }
}
