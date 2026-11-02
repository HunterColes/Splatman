package com.huntercoles.splatman.capture.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Synthetic 8x4 YUV_420_888 frame: left half grey (Y 128, U 128, V 128), right half pure red encoded with BT.601
 * full range (Y 76, U 85, V 255). Tolerance 2/255 per channel.
 */
class Yuv420Test {
    private val width = 8
    private val height = 4

    private fun expectBlocks(rgba: ByteArray) {
        assertEquals(width * height * 4, rgba.size)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val i = (row * width + col) * 4
                val px = IntArray(4) { rgba[i + it].toInt() and 0xFF }
                val want = if (col < width / 2) intArrayOf(128, 128, 128, 255) else intArrayOf(255, 0, 0, 255)
                for (c in 0 until 4) {
                    assertTrue(
                        abs(px[c] - want[c]) <= 2,
                        "pixel ($col, $row) channel $c: got ${px[c]}, want ${want[c]}",
                    )
                }
            }
        }
    }

    /** Builds the planes with the given strides; padding bytes are 0x55 so a stride bug shows. */
    private fun planes(yRowStride: Int, uvRowStride: Int, uvPixelStride: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(yRowStride * height) { 0x55 }
        for (row in 0 until height) {
            for (col in 0 until width) y[row * yRowStride + col] = (if (col < width / 2) 128 else 76).toByte()
        }
        val chromaW = width / 2
        val chromaH = height / 2
        val uvSize = uvRowStride * (chromaH - 1) + uvPixelStride * (chromaW - 1) + 1
        val u = ByteArray(uvSize) { 0x55 }
        val v = ByteArray(uvSize) { 0x55 }
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val i = row * uvRowStride + col * uvPixelStride
                val grey = col < chromaW / 2
                u[i] = (if (grey) 128 else 85).toByte()
                v[i] = (if (grey) 128 else 255).toByte()
            }
        }
        return Triple(y, u, v)
    }

    @Test
    fun planarFrameGivesGreyAndRedBlocks() {
        val (y, u, v) = planes(yRowStride = width, uvRowStride = width / 2, uvPixelStride = 1)
        expectBlocks(Yuv420.toRgba(y, u, v, Yuv420Strides(width, width / 2, 1), width, height))
    }

    @Test
    fun paddedInterleavedFrameGivesGreyAndRedBlocks() {
        val strides = Yuv420Strides(yRowStride = 12, uvRowStride = 12, uvPixelStride = 2)
        val (y, u, v) = planes(strides.yRowStride, strides.uvRowStride, strides.uvPixelStride)
        expectBlocks(Yuv420.toRgba(y, u, v, strides, width, height))
    }

    @Test
    fun blackAndWhiteStayExact() {
        val y = byteArrayOf(0, 255.toByte())
        val u = byteArrayOf(128.toByte())
        val v = byteArrayOf(128.toByte())
        val rgba = Yuv420.toRgba(y, u, v, Yuv420Strides(2, 1, 1), 2, 1).map { it.toInt() and 0xFF }
        assertEquals(listOf(0, 0, 0, 255, 255, 255, 255, 255), rgba)
    }

    @Test
    fun shortChromaPlaneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420.toRgba(ByteArray(32), ByteArray(3), ByteArray(8), Yuv420Strides(8, 4, 1), width, height)
        }
    }
}
