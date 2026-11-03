package com.huntercoles.splatman.splat.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #213: image change between 64x48 luma thumbnails, and the box downsample that makes them. */
class ImageChangeTest {

    private val w = 64
    private val h = 48

    /** 8-pixel checkerboard of 0 and 255, shifted right by [dx] pixels. */
    private fun checker(dx: Int): ByteArray = ByteArray(w * h) { i ->
        val x = i % w + dx
        val y = i / w
        if (((x / 8) + (y / 8)) % 2 == 0) 0 else 255.toByte()
    }

    @Test
    fun `A4 identical thumbnails differ by 0`() {
        val a = checker(0)
        assertEquals(0f, ImageChange.meanAbsDiff(a, a.copyOf()), 0f)
        val flat = ByteArray(w * h) { 200.toByte() }
        assertEquals(0f, ImageChange.meanAbsDiff(flat, flat.copyOf()), 0f)
    }

    @Test
    fun `A4 a shifted pattern is over the default threshold`() {
        val threshold = PoseFreeKeyframePolicy().minImageChange
        val change = ImageChange.meanAbsDiff(checker(0), checker(4))
        assertTrue(change > threshold, "shift by 4 px gave $change, threshold $threshold")
    }

    @Test
    fun `A4 bytes are unsigned luma and the mean is symmetric`() {
        val dark = ByteArray(w * h) { 10 }
        val bright = ByteArray(w * h) { 210.toByte() } // negative as a signed byte
        assertEquals(200f, ImageChange.meanAbsDiff(dark, bright), 1e-3f)
        assertEquals(200f, ImageChange.meanAbsDiff(bright, dark), 1e-3f)
        // one pixel 0 against 255, the rest equal: mean is 255 / 3072
        val base = dark.copyOf().also { it[100] = 0 }
        val one = dark.copyOf().also { it[100] = 255.toByte() }
        assertEquals(255f / (w * h), ImageChange.meanAbsDiff(base, one), 1e-4f)
    }

    @Test
    fun `A4 thumbnail of a uniform frame is uniform`() {
        val width = 640
        val height = 480
        val luma = ByteArray(width * height) { 77 }
        val out = ByteArray(w * h)
        ImageChange.thumbnail(luma, width, height, width, out)
        for (i in out.indices) assertEquals(77, out[i].toInt() and 0xFF, "pixel $i")
    }

    @Test
    fun `A4 thumbnail box-averages each block and skips the stride padding`() {
        // 128x96 frame, stride 140 (12 padding bytes of 255 per row). Each 2x2 block of the left half holds
        // 0, 100, 100, 200 (mean 100); the right half is 220. Thumbnail: left 32 columns 100, right 32 columns 220.
        val width = 128
        val height = 96
        val stride = 140
        val luma = ByteArray(stride * height) { 255.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            val v = if (x < width / 2) (x % 2 + y % 2) * 100 else 220
            luma[y * stride + x] = v.toByte()
        }
        val out = ByteArray(w * h)
        ImageChange.thumbnail(luma, width, height, stride, out)
        for (ty in 0 until h) for (tx in 0 until w) {
            val expected = if (tx < w / 2) 100 else 220
            assertEquals(expected.toDouble(), (out[ty * w + tx].toInt() and 0xFF).toDouble(), 1.0, "thumb ($tx, $ty)")
        }
    }

    @Test
    fun `A4 thumbnail and meanAbsDiff allocate nothing`() {
        val width = 640
        val height = 480
        val luma = ByteArray(width * height) { (it * 31).toByte() }
        val a = ByteArray(w * h)
        val b = ByteArray(w * h)
        repeat(20) {
            ImageChange.thumbnail(luma, width, height, width, a)
            ImageChange.meanAbsDiff(a, b)
        }
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(100) {
            ImageChange.thumbnail(luma, width, height, width, a)
            ImageChange.meanAbsDiff(a, b)
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 16_000, "100 thumbnails allocated $allocated bytes")
    }
}
