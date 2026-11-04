package com.huntercoles.splatman.splat.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * #328: markUnderBrush, centre under a capsule through the depth, w <= 0 never hit (ADR 0014 rules 1, 3).
 * Surface 200 x 200 px, so NDC x maps to px = 100 + 100 * x. Every brush point sits on the row y = 100 and every
 * case is mirror-symmetric about it, so the tests hold whichever way the implementation flips y.
 */
class BrushHitTest {

    private val w = 200
    private val h = 200

    /** clip = (x, y, z, 1). */
    private val identity = FloatArray(16).also {
        it[0] = 1f
        it[5] = 1f
        it[10] = 1f
        it[15] = 1f
    }

    /** clip = (x, y, z, -z), column-major: a camera at the origin looking down -z. */
    private val lookDownMinusZ = FloatArray(16).also {
        it[0] = 1f
        it[5] = 1f
        it[10] = 1f
        it[11] = -1f
    }

    private fun hits(positions: FloatArray, viewProj: FloatArray, points: FloatArray, radius: Float): List<Boolean> {
        val mask = EraseMask(positions.size / 3)
        markUnderBrush(positions, viewProj, w, h, points, radius, mask)
        return (0 until mask.count).map { mask.isMarked(it) }
    }

    @Test
    fun `A1 a centre exactly on the capsule edge is hit and one just outside is not`() {
        // Segment (50,100)-(150,100), radius 25. Side: px y 75/125 (dist 25) hit, 74/126 (dist 26) not.
        // End caps: px x 175 and 25 (dist 25 from an end) hit, 175.01 and 24.99 not.
        val positions = floatArrayOf(
            0f, 0.25f, 0f,
            0f, 0.26f, 0f,
            0.75f, 0f, 0f,
            0.7501f, 0f, 0f,
            -0.75f, 0f, 0f,
            -0.7501f, 0f, 0f,
        )
        val got = hits(positions, identity, floatArrayOf(50f, 100f, 150f, 100f), 25f)
        assertEquals(listOf(true, false, true, false, true, false), got)
    }

    @Test
    fun `A1 a centre behind the camera is never hit even when its projection falls under the brush`() {
        // (0,0,1): w = -1, projects to NDC (0,0) = the brush centre. (0.5,0,0): w = 0. (0,0,-1): w = 1, control.
        // (-0.1,0,2): w = -2, NDC x 0.05 -> px 105, under the brush.
        val positions = floatArrayOf(
            0f, 0f, 1f,
            0.5f, 0f, 0f,
            0f, 0f, -1f,
            -0.1f, 0f, 2f,
        )
        val got = hits(positions, lookDownMinusZ, floatArrayOf(100f, 100f), 30f)
        assertEquals(listOf(false, false, true, false), got)
    }

    @Test
    fun `A1 a single point is a circle`() {
        // Tap at (100,100), radius 25. Offsets (12,16) dist 20 hit; (24,0) hit; (18,18) dist 25.5 not (a square
        // brush would take it); (0,30) not; (-12,-16) hit.
        val positions = floatArrayOf(
            0.12f, 0.16f, 0f,
            0.24f, 0f, 0f,
            0.18f, 0.18f, 0f,
            0f, 0.3f, 0f,
            -0.12f, -0.16f, 0f,
        )
        val got = hits(positions, identity, floatArrayOf(100f, 100f), 25f)
        assertEquals(listOf(true, true, false, false, true), got)
    }

    @Test
    fun `A1 splats at different depths under the brush are all hit`() {
        val positions = floatArrayOf(
            0f, 0f, -1f,
            0f, 0f, -5f,
            0f, 0f, -50f,
            0.2f, 0f, -2f, // NDC x 0.1 -> px 110
            0f, 0f, -1000f,
            0.6f, 0f, -1f, // NDC x 0.6 -> px 160, outside
        )
        val mask = EraseMask(6)
        val newly = markUnderBrush(positions, lookDownMinusZ, w, h, floatArrayOf(100f, 100f), 20f, mask)
        assertEquals(5, newly)
        assertEquals(listOf(true, true, true, true, true, false), (0 until 6).map { mask.isMarked(it) })
    }

    @Test
    fun `A1 a polyline hits along every segment and returns only splats newly marked`() {
        // (20,100)-(100,100)-(180,100), radius 15: the first point, beside the joint, the last point; not 50 px off.
        val positions = floatArrayOf(
            -0.8f, 0f, 0f,
            0f, 0.1f, 0f,
            0.8f, 0f, 0f,
            0f, 0.5f, 0f,
        )
        val mask = EraseMask(4)
        val points = floatArrayOf(20f, 100f, 100f, 100f, 180f, 100f)
        assertEquals(3, markUnderBrush(positions, identity, w, h, points, 15f, mask))
        assertFalse(mask.isMarked(3))
        assertEquals(0, markUnderBrush(positions, identity, w, h, points, 15f, mask), "already marked")
        assertEquals(3, mask.marked)
    }

    @Test
    fun `H1 no allocation per splat in markUnderBrush`() {
        val n = 1_000_000
        val rnd = Random(328)
        val positions = FloatArray(3 * n) { rnd.nextFloat() * 2f - 1f }
        val points = floatArrayOf(40f, 100f, 100f, 60f, 160f, 140f)
        markUnderBrush(positions, identity, w, h, points, 30f, EraseMask(n)) // warm up
        val mask = EraseMask(n)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = mx.currentThreadAllocatedBytes
        val newly = markUnderBrush(positions, identity, w, h, points, 30f, mask)
        val allocated = mx.currentThreadAllocatedBytes - before
        assertTrue(newly > 0, "the brush hit something")
        assertTrue(allocated < 256 * 1024, "allocated $allocated bytes over $n splats")
    }
}
