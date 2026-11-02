package com.huntercoles.splatman.splat.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SharpnessTest {

    private val w = 64
    private val h = 48

    private fun checkerboard(square: Int, stride: Int = w): ByteArray {
        val out = ByteArray(stride * h) { 77 } // padding past the width must be ignored
        for (y in 0 until h) for (x in 0 until w) {
            out[y * stride + x] = if (((x / square) + (y / square)) % 2 == 0) 0 else 255.toByte()
        }
        return out
    }

    /** 5x5 box blur with clamped edges. */
    private fun boxBlur(src: ByteArray): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                s += src[yy * w + xx].toInt() and 0xFF
            }
            out[y * w + x] = (s / 25).toByte()
        }
        return out
    }

    @Test
    fun `a checkerboard scores higher than its box-blurred copy`() {
        val sharp = checkerboard(8)
        val sharpScore = Sharpness.laplacianVariance(sharp, w, h, w)
        val blurScore = Sharpness.laplacianVariance(boxBlur(sharp), w, h, w)
        assertTrue(sharpScore > 0f)
        assertTrue(sharpScore > 2f * blurScore, "sharp $sharpScore blurred $blurScore")
    }

    @Test
    fun `a flat image scores 0`() {
        assertEquals(0f, Sharpness.laplacianVariance(ByteArray(w * h) { 200.toByte() }, w, h, w))
        assertEquals(0f, Sharpness.laplacianVariance(ByteArray(w * h), w, h, w, downsample = 1))
    }

    @Test
    fun `luma is read as unsigned and the row stride is honoured`() {
        val stride = w + 16
        assertEquals(
            Sharpness.laplacianVariance(checkerboard(8), w, h, w),
            Sharpness.laplacianVariance(checkerboard(8, stride), w, h, stride),
        )
    }

    @Test
    fun `downsampling averages blocks`() {
        // 1-pixel checkerboard: every 2x2 block averages to 127.5, so the downsampled plane is flat
        assertEquals(0f, Sharpness.laplacianVariance(checkerboard(1), w, h, w, downsample = 2))
        assertTrue(Sharpness.laplacianVariance(checkerboard(1), w, h, w, downsample = 1) > 0f)
    }

    @Test
    fun `tiny images score 0 and bad sizes are rejected`() {
        assertEquals(0f, Sharpness.laplacianVariance(ByteArray(4), 2, 2, 2, downsample = 1))
        assertThrows<IllegalArgumentException> { Sharpness.laplacianVariance(ByteArray(10), 4, 4, 4) }
        assertThrows<IllegalArgumentException> { Sharpness.laplacianVariance(ByteArray(16), 4, 4, 3) }
    }
}
