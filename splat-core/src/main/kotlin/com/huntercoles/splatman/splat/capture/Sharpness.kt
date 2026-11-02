package com.huntercoles.splatman.splat.capture

/** Image sharpness for keyframe selection (ADR 0004). Pure and allocation-free. */
object Sharpness {

    /** Default downsampling factor: a 640x480 CPU image is scored at 320x240. */
    const val DEFAULT_DOWNSAMPLE = 2

    /**
     * Variance of the 4-neighbour Laplacian of the luma plane, after box-downsampling by [downsample] (each output
     * pixel is the mean of a `downsample x downsample` block; edge remainders are dropped). [luma] holds 8-bit
     * unsigned values, row `y` starting at `y * stride`. Higher is sharper; a flat image scores 0, and so does an
     * image smaller than 3x3 after downsampling. Units: luma levels squared.
     */
    fun laplacianVariance(
        luma: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        downsample: Int = DEFAULT_DOWNSAMPLE,
    ): Float {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        require(stride >= width) { "stride $stride is less than width $width" }
        require(downsample >= 1) { "downsample must be >= 1, got $downsample" }
        require(luma.size >= (height - 1) * stride + width) {
            "luma holds ${luma.size} bytes, a ${width}x$height plane with stride $stride needs ${(height - 1) * stride + width}"
        }
        val w = width / downsample
        val h = height / downsample
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val lap = block(luma, stride, downsample, x - 1, y) + block(luma, stride, downsample, x + 1, y) +
                    block(luma, stride, downsample, x, y - 1) + block(luma, stride, downsample, x, y + 1) -
                    4.0 * block(luma, stride, downsample, x, y)
                sum += lap
                sumSq += lap * lap
            }
        }
        val n = ((w - 2) * (h - 2)).toDouble()
        val mean = sum / n
        return maxOf(0.0, sumSq / n - mean * mean).toFloat()
    }

    /** Mean luma of downsampled pixel ([x], [y]). */
    private fun block(luma: ByteArray, stride: Int, k: Int, x: Int, y: Int): Double {
        if (k == 1) return (luma[y * stride + x].toInt() and 0xFF).toDouble()
        var s = 0
        val x0 = x * k
        for (yy in y * k until (y + 1) * k) {
            val row = yy * stride
            for (xx in x0 until x0 + k) s += luma[row + xx].toInt() and 0xFF
        }
        return s.toDouble() / (k * k)
    }
}
