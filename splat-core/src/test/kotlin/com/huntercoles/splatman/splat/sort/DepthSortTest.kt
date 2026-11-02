package com.huntercoles.splatman.splat.sort

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.management.ManagementFactory
import kotlin.random.Random

class DepthSortTest {

    private fun sort(depths: FloatArray): IntArray {
        val out = IntArray(depths.size) { -1 }
        DepthSort().backToFront(depths, depths.size, out)
        return out
    }

    private fun assertPermutation(out: IntArray, n: Int) {
        val seen = BooleanArray(n)
        for (i in 0 until n) {
            assertTrue(!seen[out[i]], "index ${out[i]} twice")
            seen[out[i]] = true
        }
    }

    @Test
    fun `100k seeded depths come out like sortedDescending up to key quantisation`() {
        val rnd = Random(27)
        val n = 100_000
        val depths = FloatArray(n) { 0.3f + rnd.nextFloat() * 50f }
        val out = sort(depths)
        assertPermutation(out, n)
        val bin = (depths.max() - depths.min()) / 65535f
        for (i in 1 until n) {
            assertTrue(depths[out[i - 1]] >= depths[out[i]] - bin, "position $i out of order")
        }
        val reference = depths.sortedDescending()
        for (i in 0 until n) assertEquals(reference[i], depths[out[i]], bin, "position $i")
    }

    @Test
    fun `distinct keys sort exactly`() {
        val depths = floatArrayOf(3f, 1f, 4f, 1.5f, 9f, 2.6f, 5f)
        assertArrayEquals(intArrayOf(4, 6, 2, 0, 5, 3, 1), sort(depths))
    }

    @Test
    fun `ties keep index order`() {
        val depths = floatArrayOf(2f, 5f, 2f, 5f, 1f, 2f, 5f)
        assertArrayEquals(intArrayOf(1, 3, 6, 0, 2, 5, 4), sort(depths))
    }

    @Test
    fun `all equal keeps index order`() {
        val depths = FloatArray(1000) { 4.2f }
        assertArrayEquals(IntArray(1000) { it }, sort(depths))
    }

    @Test
    fun `N 0 and 1`() {
        val out = IntArray(3) { -1 }
        DepthSort().backToFront(floatArrayOf(1f, 2f), 0, out)
        assertArrayEquals(intArrayOf(-1, -1, -1), out)
        DepthSort().backToFront(floatArrayOf(7f, 2f), 1, out)
        assertArrayEquals(intArrayOf(0, -1, -1), out)
    }

    @Test
    fun `only the first count depths are read and written`() {
        val out = IntArray(4) { -1 }
        DepthSort().backToFront(floatArrayOf(1f, 3f, 2f, 100f), 3, out)
        assertArrayEquals(intArrayOf(1, 2, 0, -1), out)
        assertThrows<IllegalArgumentException> { DepthSort().backToFront(FloatArray(2), 3, IntArray(3)) }
        assertThrows<IllegalArgumentException> { DepthSort().backToFront(FloatArray(3), 3, IntArray(2)) }
    }

    @Test
    fun `NaN depths come first in index order, infinities at the ends`() {
        val nan = Float.NaN
        val depths = floatArrayOf(2f, nan, Float.NEGATIVE_INFINITY, 8f, nan, Float.POSITIVE_INFINITY, 5f)
        assertArrayEquals(intArrayOf(1, 4, 5, 3, 6, 0, 2), sort(depths))
        assertArrayEquals(intArrayOf(0, 1), sort(floatArrayOf(nan, nan)))
    }

    @Test
    fun `reuse across sizes and no allocation per call once warm`() {
        val sorter = DepthSort()
        val rnd = Random(5)
        val small = FloatArray(10) { rnd.nextFloat() }
        val big = FloatArray(5000) { rnd.nextFloat() * 10f }
        val out = IntArray(5000)
        sorter.backToFront(big, big.size, out)
        sorter.backToFront(small, small.size, out)
        assertArrayEquals(sort(small), out.copyOf(10))
        repeat(50) { sorter.backToFront(big, big.size, out) } // warm up
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(100) { sorter.backToFront(big, big.size, out) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 16_000, "100 sorts of 5000 allocated $allocated bytes")
        assertArrayEquals(sort(big), out)
    }
}
