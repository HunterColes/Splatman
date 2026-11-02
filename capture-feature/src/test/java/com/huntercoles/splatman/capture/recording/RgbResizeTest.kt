package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.RgbImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RgbResizeTest {
    private val k = CameraIntrinsics(fx = 4f, fy = 4f, cx = 1.5f, cy = 0.5f, width = 4, height = 2)

    @Test
    fun `the right size is returned as it is`() {
        val image = RgbImage(4, 2, ByteArray(32))
        val sized = RgbResize.toSize(image, k, 4, 2)
        assertSame(image, sized.image)
        assertSame(k, sized.intrinsics)
    }

    @Test
    fun `halving averages each 2x2 block and scales the intrinsics`() {
        // Row 0: 0, 100, 200, 40; row 1: 20, 60, 0, 120 (red); green and blue 0; alpha 255.
        val reds = intArrayOf(0, 100, 200, 40, 20, 60, 0, 120)
        val rgba = ByteArray(32)
        reds.forEachIndexed { i, r ->
            rgba[4 * i] = r.toByte()
            rgba[4 * i + 3] = 0xFF.toByte()
        }
        val sized = RgbResize.toSize(RgbImage(4, 2, rgba), k, 2, 1)
        assertArrayEquals(byteArrayOf(45, 0, 0, 0xFF.toByte(), 90, 0, 0, 0xFF.toByte()), sized.image.rgba)
        assertEquals(k.scaled(2, 1), sized.intrinsics)
        assertEquals(2f, sized.intrinsics.fx)
        assertEquals(0.5f, sized.intrinsics.cx)
    }
}
