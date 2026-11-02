package com.huntercoles.splatman.splat.gpu.raster

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class SortKeysTest {
    @Test
    fun `depth keys are the float bits and order like the depths`() {
        assertEquals(0x3E4CCCCD, SortKeys.depthKey(0.2f))
        assertEquals(1f.toRawBits(), SortKeys.depthKey(1f))
        val r = Random(42)
        repeat(10_000) {
            val a = 0.2f + r.nextFloat() * 100f
            val b = 0.2f + r.nextFloat() * 100f
            val ka = SortKeys.depthKey(a)
            val kb = SortKeys.depthKey(b)
            assertEquals(a.compareTo(b).coerceIn(-1, 1), Integer.compareUnsigned(ka, kb).coerceIn(-1, 1), "$a vs $b")
        }
        // Neighbouring floats get neighbouring keys: no two depths share one.
        val d = 3.25f
        assertEquals(SortKeys.depthKey(d) + 1, SortKeys.depthKey(Math.nextUp(d)))
    }

    @Test
    fun `non-positive depths have no key`() {
        for (d in floatArrayOf(0f, -0f, -1f, Float.NaN)) assertThrows<IllegalArgumentException> { SortKeys.depthKey(d) }
    }

    @Test
    fun `tile passes cover only the bits the highest tile needs`() {
        val cases = mapOf(
            1 to (0 to 4), 2 to (1 to 5), 16 to (4 to 5), 256 to (8 to 5), 257 to (9 to 6),
            300 to (9 to 6), 65_536 to (16 to 6), 65_537 to (17 to 7), 1 shl 24 to (24 to 7), (1 shl 24) + 1 to (25 to 8),
        )
        for ((tiles, want) in cases) {
            assertEquals(want.first, SortKeys.tileBits(tiles), "bits for $tiles tiles")
            assertEquals(want.second, SortKeys.passCount(tiles), "passes for $tiles tiles")
        }
        // The two parity scenes: 64x64 is 4x4 tiles, 320x240 is 20x15.
        assertEquals(5, SortKeys.passCount(16))
        assertEquals(6, SortKeys.passCount(300))
        assertThrows<IllegalArgumentException> { SortKeys.tileBits(0) }
    }

    @Test
    fun `passes run the depth bytes low to high, then the tile bytes`() {
        val words = IntArray(7) { SortKeys.word(it) }
        val shifts = IntArray(7) { SortKeys.shift(it) }
        val d = SortKeys.DEPTH_WORD
        val t = SortKeys.TILE_WORD
        assertArrayEquals(intArrayOf(d, d, d, d, t, t, t), words)
        assertArrayEquals(intArrayOf(0, 8, 16, 24, 0, 8, 16), shifts)
    }

    @Test
    fun `blocks and scan levels`() {
        assertEquals(0, SortKeys.blocks(0))
        assertEquals(1, SortKeys.blocks(256))
        assertEquals(2, SortKeys.blocks(257))
        assertEquals(0, ScanPlan.levels(0).size)
        assertArrayEquals(intArrayOf(1), ScanPlan.levels(1))
        assertArrayEquals(intArrayOf(1024), ScanPlan.levels(1024))
        assertArrayEquals(intArrayOf(1025, 2), ScanPlan.levels(1025))
        assertArrayEquals(intArrayOf(1 shl 20, 1024), ScanPlan.levels(1 shl 20))
        assertArrayEquals(intArrayOf((1 shl 20) + 1, 1025, 2), ScanPlan.levels((1 shl 20) + 1))
        assertEquals(SortKeys.BLOCK * 65_535, SortKeys.MAX_ENTRIES)
        assertTrue(ScanPlan.BLOCK == BinShaders.LOCAL * ScanPlan.PER_THREAD)
    }
}
