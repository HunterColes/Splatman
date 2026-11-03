package com.huntercoles.splatman.splat.capture

import kotlin.math.abs

/**
 * How much the view changed between two frames, measured on small luma thumbnails ([WIDTH] x [HEIGHT], unsigned
 * bytes, row-major). Allocation-free.
 */
object ImageChange {
    const val WIDTH = 64
    const val HEIGHT = 48
    const val SIZE = WIDTH * HEIGHT

    /** Mean absolute difference of two equal-size unsigned-luma thumbnails, in luma levels 0..255. */
    fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "thumbnails must be the same non-zero size" }
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return (sum.toDouble() / a.size).toFloat()
    }

    /**
     * Box-downsamples a [width] x [height] luma plane with row [stride] (bytes) into [out] ([SIZE] bytes): each
     * thumbnail pixel is the rounded mean of its block of source pixels. Padding past [width] is never read.
     */
    fun thumbnail(luma: ByteArray, width: Int, height: Int, stride: Int, out: ByteArray) {
        require(width >= 1 && height >= 1 && stride >= width) { "bad frame size" }
        require(luma.size >= (height - 1) * stride + width) { "luma too small for the frame" }
        require(out.size >= SIZE) { "out must hold $SIZE bytes" }
        for (ty in 0 until HEIGHT) {
            val y0 = ty * height / HEIGHT
            val y1 = maxOf((ty + 1) * height / HEIGHT, y0 + 1)
            for (tx in 0 until WIDTH) {
                val x0 = tx * width / WIDTH
                val x1 = maxOf((tx + 1) * width / WIDTH, x0 + 1)
                var sum = 0L
                for (y in y0 until y1) {
                    val row = y * stride
                    for (x in x0 until x1) sum += luma[row + x].toInt() and 0xFF
                }
                val n = (y1 - y0) * (x1 - x0)
                out[ty * WIDTH + tx] = ((sum + n / 2) / n).toInt().toByte()
            }
        }
    }
}
