package com.huntercoles.splatman.splat.edit

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** #328: markUnderBrush over disjoint range chunks, in parallel on one mask, equals one whole pass. */
class BrushChunkTest {

    private val identity = FloatArray(16).also {
        it[0] = 1f
        it[5] = 1f
        it[10] = 1f
        it[15] = 1f
    }
    private val points = floatArrayOf(40f, 100f, 100f, 60f, 160f, 140f)

    @Test
    fun `parallel chunks mark the same splats and counts as one pass`() {
        val n = 100_003
        val rnd = Random(3280)
        val positions = FloatArray(3 * n) { rnd.nextFloat() * 2f - 1f }
        val whole = EraseMask(n)
        val newlyWhole = markUnderBrush(positions, identity, 200, 200, points, 30f, whole)

        val chunked = EraseMask(n)
        val chunks = 7
        val pool = Executors.newFixedThreadPool(4)
        val futures = (0 until chunks).map { c ->
            val from = n * c / chunks
            val end = n * (c + 1) / chunks
            pool.submit<Int> { markUnderBrush(positions, identity, 200, 200, points, 30f, chunked, from until end) }
        }
        val newlyChunked = futures.sumOf { it.get() }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertEquals(newlyWhole, newlyChunked)
        assertEquals(whole.marked, chunked.marked)
        assertEquals(whole.lastMarked, chunked.lastMarked)
        assertArrayEquals(whole.bytes, chunked.bytes)
    }

    @Test
    fun `a chunk touches only its own range`() {
        val positions = FloatArray(3 * 4) // every centre at the origin -> pixel (100, 100)
        val mask = EraseMask(4)
        assertEquals(2, markUnderBrush(positions, identity, 200, 200, floatArrayOf(100f, 100f), 5f, mask, 1 until 3))
        assertEquals(listOf(false, true, true, false), (0 until 4).map { mask.isMarked(it) })
    }

    @Test
    fun `a range outside the mask is refused`() {
        val mask = EraseMask(4)
        assertThrows<IllegalArgumentException> {
            markUnderBrush(FloatArray(12), identity, 200, 200, points, 5f, mask, 2 until 5)
        }
    }
}
