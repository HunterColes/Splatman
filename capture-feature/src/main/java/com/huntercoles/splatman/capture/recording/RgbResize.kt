package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.RgbImage
import kotlin.math.floor

/** An RGBA image with the intrinsics that describe it. */
class SizedImage(val image: RgbImage, val intrinsics: CameraIntrinsics)

/** Resizing keyframe images to the recording size, keeping the intrinsics in step (pure). */
object RgbResize {

    /**
     * [image] (described by [intrinsics]) at `width x height`: unchanged when it already has that size, otherwise
     * resampled bilinearly with the pixel-centre convention of [CameraIntrinsics.scaled] (destination pixel `i`
     * samples source `(i + 0.5) * s - 0.5`, `s` = source / destination size), and the intrinsics scaled to match.
     * Halving is then an exact 2x2 box average.
     */
    fun toSize(image: RgbImage, intrinsics: CameraIntrinsics, width: Int, height: Int): SizedImage {
        require(width > 0 && height > 0) { "size must be positive, got ${width}x$height" }
        if (image.width == width && image.height == height) return SizedImage(image, intrinsics)
        val sx = image.width.toFloat() / width
        val sy = image.height.toFloat() / height
        val src = image.rgba
        val sw = image.width
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (image.height - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = minOf(y0 + 1, image.height - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = minOf(x0 + 1, sw - 1)
                val tx = fx - x0
                val a = (y0 * sw + x0) * 4
                val b = (y0 * sw + x1) * 4
                val c = (y1 * sw + x0) * 4
                val d = (y1 * sw + x1) * 4
                val o = (y * width + x) * 4
                for (ch in 0 until 4) {
                    val top = (src[a + ch].toInt() and 0xFF) * (1f - tx) + (src[b + ch].toInt() and 0xFF) * tx
                    val bottom = (src[c + ch].toInt() and 0xFF) * (1f - tx) + (src[d + ch].toInt() and 0xFF) * tx
                    out[o + ch] = (top * (1f - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return SizedImage(RgbImage(width, height, out), intrinsics.scaled(width, height))
    }
}
