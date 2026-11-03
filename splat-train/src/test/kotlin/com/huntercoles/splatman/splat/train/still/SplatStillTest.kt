package com.huntercoles.splatman.splat.train.still

import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.random.Random

class SplatStillTest {

    private val background = 0xFF0A0A0D.toInt()

    /** A cloud of white splats of [scale] metres at [positions] (3 floats each) with the given opacity logits. */
    private fun cloud(positions: FloatArray, logits: FloatArray, scale: Float = 0.05f): SplatCloud {
        val n = logits.size
        return SplatCloud(
            n, 0, positions,
            FloatArray(3 * n) { ln(scale) },
            FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            logits,
            FloatArray(3 * n) { WHITE_DC },
            FloatArray(0),
        )
    }

    private fun centre(pixels: IntArray) =
        pixels[(SplatStill.DEFAULT_HEIGHT / 2) * SplatStill.DEFAULT_WIDTH + SplatStill.DEFAULT_WIDTH / 2]

    @Test
    fun `the output is 256 by 320`() {
        val pixels = SplatStill.render(cloud(floatArrayOf(1f, 2f, -3f), floatArrayOf(5f)), background = background)
        assertEquals(256 * 320, pixels.size)
        assertEquals(SplatStill.DEFAULT_WIDTH * 5, SplatStill.DEFAULT_HEIGHT * 4, "4:5")
    }

    @Test
    fun `one bright splat at the bounds centre lights the image centre`() {
        val pixels = SplatStill.render(cloud(floatArrayOf(1f, 2f, -3f), floatArrayOf(5f)), background = background)
        val c = centre(pixels)
        assertNotEquals(background, c)
        assertTrue((c shr 16 and 0xFF) > 0x80, "centre is bright: ${Integer.toHexString(c)}")
        assertEquals(background, pixels[0], "the corner stays background")
    }

    @Test
    fun `an empty cloud is all background`() {
        val pixels = SplatStill.render(cloud(FloatArray(0), FloatArray(0)), background = background)
        assertEquals(256 * 320, pixels.size)
        assertTrue(pixels.all { it == background })
    }

    @Test
    fun `the farthest one percent do not move the framing`() {
        // 200 splats within 0.5 m of the origin and one far outlier: the radius ignores the outlier.
        val random = Random(7)
        val n = 201
        val positions = FloatArray(3 * n) { (random.nextFloat() - 0.5f) * 0.5f }
        positions[3 * 200] = 1000f
        val (_, radius) = SplatStill.robustBounds(cloud(positions, FloatArray(n) { 0f }))
        assertTrue(radius < 1f, "radius $radius")
    }

    @Test
    fun `non-finite splats are skipped without a crash`() {
        val positions = floatArrayOf(
            1f, 2f, -3f,
            Float.NaN, 0f, 0f,
            0f, Float.POSITIVE_INFINITY, 0f,
            0f, 0f, Float.NEGATIVE_INFINITY,
        )
        val c = cloud(positions, floatArrayOf(5f, 5f, 5f, 5f))
        assertEquals(1, SplatStill.prepare(c).count)
        val pixels = SplatStill.render(c, background = background)
        assertNotEquals(background, centre(pixels))
    }

    @Test
    fun `a million splats are capped to the most opaque 200k`() {
        val n = 1_000_000
        val random = Random(3)
        val positions = FloatArray(3 * n) { random.nextFloat() * 2f - 1f }
        val logits = FloatArray(n) { random.nextFloat() * 8f - 4f }
        val big = cloud(positions, logits, scale = 0.005f)

        val drawn = SplatStill.prepare(big)
        assertEquals(SplatStill.MAX_SPLATS, drawn.count)
        val minKept = drawn.opacityLogits.min()
        val sorted = logits.sortedArray()
        assertEquals(sorted[n - SplatStill.MAX_SPLATS], minKept)

        val start = System.nanoTime()
        val pixels = SplatStill.render(big, background = background)
        val seconds = (System.nanoTime() - start) / 1e9
        assertEquals(256 * 320, pixels.size)
        assertTrue(seconds < BUDGET_S, "a 1M-splat still took $seconds s")
    }

    private companion object {
        /** SH degree-0 value giving colour 1: (1 - 0.5) / C0. */
        const val WHITE_DC = 1.7725f

        /** The card's 2 s on the desktop JVM, with headroom for a loaded gate machine. */
        const val BUDGET_S = 10.0
    }
}
