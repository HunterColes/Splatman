package com.huntercoles.splatman.capture.tracking

/** Row and pixel strides of a YUV_420_888 image, bytes, as `Image.Plane` reports them. */
data class Yuv420Strides(
    /** Bytes between rows of the Y plane. */
    val yRowStride: Int,
    /** Bytes between rows of the U and V planes. */
    val uvRowStride: Int,
    /** Bytes between neighbouring chroma samples in a U or V row (1 planar, 2 interleaved). */
    val uvPixelStride: Int,
)

/** YUV_420_888 -> RGBA8888, pure. */
object Yuv420 {
    private const val SHIFT = 16
    private const val HALF = 1 shl (SHIFT - 1)
    private const val CHROMA_ZERO = 128

    // BT.601 full range (JFIF) coefficients, fixed point * 2^16.
    private const val R_V = 91_881 // 1.402
    private const val G_U = 22_554 // 0.344136
    private const val G_V = 46_802 // 0.714136
    private const val B_U = 116_130 // 1.772

    /**
     * Converts one YUV_420_888 image ([width] x [height], chroma at half resolution in both axes) to RGBA8888,
     * row-major from the top-left pixel, alpha 255, with BT.601 full-range coefficients (what the camera's JPEG uses).
     */
    fun toRgba(y: ByteArray, u: ByteArray, v: ByteArray, strides: Yuv420Strides, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        require(y.size >= strides.yRowStride * (height - 1) + width) { "Y plane too small: ${y.size} bytes" }
        val uvNeeded = strides.uvRowStride * (chromaH - 1) + strides.uvPixelStride * (chromaW - 1) + 1
        require(u.size >= uvNeeded && v.size >= uvNeeded) {
            "chroma planes too small: need $uvNeeded bytes, got U ${u.size}, V ${v.size}"
        }
        val out = ByteArray(width * height * 4)
        var o = 0
        for (row in 0 until height) {
            val yRow = row * strides.yRowStride
            val uvRow = (row shr 1) * strides.uvRowStride
            for (col in 0 until width) {
                val luma = (y[yRow + col].toInt() and 0xFF) shl SHIFT
                val c = uvRow + (col shr 1) * strides.uvPixelStride
                val cb = (u[c].toInt() and 0xFF) - CHROMA_ZERO
                val cr = (v[c].toInt() and 0xFF) - CHROMA_ZERO
                out[o] = clamp((luma + R_V * cr + HALF) shr SHIFT)
                out[o + 1] = clamp((luma - G_U * cb - G_V * cr + HALF) shr SHIFT)
                out[o + 2] = clamp((luma + B_U * cb + HALF) shr SHIFT)
                out[o + 3] = 0xFF.toByte()
                o += 4
            }
        }
        return out
    }

    private fun clamp(value: Int): Byte = value.coerceIn(0, 255).toByte()
}
