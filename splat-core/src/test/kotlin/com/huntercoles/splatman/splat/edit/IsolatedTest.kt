package com.huntercoles.splatman.splat.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** #329: markIsolated, the Remove isolated splats pass (ADR 0014 rule 8). */
class IsolatedTest {

    private val base = 8 * 8 * 8 * 5

    /** An 8x8x8 lattice, 0.1 m apart, of tight clumps of 5 splats each, then [extra] centres. */
    private fun dense(vararg extra: Float): FloatArray {
        val r = Random(7)
        val out = ArrayList<Float>()
        for (x in 0 until 8) for (y in 0 until 8) for (z in 0 until 8) repeat(5) {
            out += x * 0.1f + r.nextFloat() * 0.001f
            out += y * 0.1f + r.nextFloat() * 0.001f
            out += z * 0.1f + r.nextFloat() * 0.001f
        }
        extra.forEach { out += it }
        return out.toFloatArray()
    }

    private fun marked(mask: EraseMask) = (0 until mask.count).filter { mask.isMarked(it) }

    @Test
    fun `marks exactly the lone splats inside and outside the central box`() {
        val cloud = cloudAt(
            dense(
                0.35f, 0.35f, 0.35f, // inside the fitCentral box, between clumps
                -0.25f, 0.3f, 0.3f, // in the grown grid, outside fitCentral
                5f, 5f, 5f, // far outside the grid
            ),
        )
        val mask = EraseMask(cloud.count)
        assertEquals(3, markIsolated(cloud, mask))
        assertEquals(listOf(base, base + 1, base + 2), marked(mask))
    }

    @Test
    fun `a close pair is marked and a cluster of four is not`() {
        val cloud = cloudAt(
            dense(
                -0.25f, 0.3f, 0.3f, -0.2505f, 0.3f, 0.3f,
                0.35f, 0.35f, 0.35f, 0.3501f, 0.35f, 0.35f, 0.35f, 0.3501f, 0.35f, 0.35f, 0.35f, 0.3501f,
            ),
        )
        val mask = EraseMask(cloud.count)
        assertEquals(2, markIsolated(cloud, mask))
        assertEquals(listOf(base, base + 1), marked(mask))
    }

    @Test
    fun `undo clears the whole pass and keeps earlier strokes`() {
        val cloud = cloudAt(dense(0.35f, 0.35f, 0.35f, 5f, 5f, 5f))
        val mask = EraseMask(cloud.count)
        mask.beginStroke()
        mask.mark(0)
        assertEquals(2, markIsolated(cloud, mask))
        assertEquals(3, mask.marked)
        assertTrue(mask.undo())
        assertEquals(listOf(0), marked(mask))
    }

    @Test
    fun `empty and single splat clouds do not throw`() {
        assertEquals(0, markIsolated(cloudAt(FloatArray(0)), EraseMask(0)))
        val one = EraseMask(1)
        assertEquals(1, markIsolated(cloudAt(floatArrayOf(1f, 2f, 3f)), one))
        assertTrue(one.isMarked(0))
        val nan = EraseMask(1)
        assertEquals(0, markIsolated(cloudAt(floatArrayOf(Float.NaN, 0f, 0f)), nan))
        assertFalse(nan.isMarked(0))
    }
}
