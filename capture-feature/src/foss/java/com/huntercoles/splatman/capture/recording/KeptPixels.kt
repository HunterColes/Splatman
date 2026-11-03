package com.huntercoles.splatman.capture.recording

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.huntercoles.splatman.splat.model.RgbImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** A keyframe's pixels copied out of its camera image (#340), sensor orientation. */
sealed interface KeptPixels {
    /** Width, pixels. */
    val width: Int

    /** Height, pixels. */
    val height: Int

    /** Already RGB (a source without NV21 planes, such as the tests' fake). */
    class Rgb(val image: RgbImage) : KeptPixels {
        override val width: Int get() = image.width
        override val height: Int get() = image.height
    }

    /** NV21: the Y plane, then V and U interleaved at half resolution; [YuvImage] encodes it natively. */
    class Nv21(override val width: Int, override val height: Int, val bytes: ByteArray) : KeptPixels
}

/** Encodes [KeptPixels.Nv21] to JPEG. */
fun interface Nv21JpegEncoder {
    /** JPEG bytes at [quality] 0..100; throws [IOException] when it cannot. */
    fun encode(pixels: KeptPixels.Nv21, quality: Int): ByteArray
}

/**
 * [Nv21JpegEncoder] through [YuvImage.compressToJpeg]: libjpeg takes the camera's full-range BT.601 YUV as it is, so
 * no per-pixel Kotlin runs (the RGB path converted and repacked 2 million pixels in Kotlin per keyframe).
 */
object YuvImageJpegEncoder : Nv21JpegEncoder {
    override fun encode(pixels: KeptPixels.Nv21, quality: Int): ByteArray {
        val yuv = YuvImage(pixels.bytes, ImageFormat.NV21, pixels.width, pixels.height, null)
        val out = ByteArrayOutputStream(pixels.width * pixels.height / 4)
        if (!yuv.compressToJpeg(Rect(0, 0, pixels.width, pixels.height), quality, out)) {
            throw IOException("JPEG encoding failed")
        }
        return out.toByteArray()
    }
}

/** YUV_420_888 planes to NV21 by bulk row copies; the only per-pixel loop is the half-resolution chroma interleave. */
internal object Nv21 {
    /** NV21 of a [width] x [height] (both even) image from its planes; the buffers' positions are left alone. */
    @Suppress("LongParameterList") // the plane layout of YUV_420_888
    fun copy(
        width: Int,
        height: Int,
        y: ByteBuffer,
        yRowStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "bad size ${width}x$height" }
        require(yRowStride >= width && uvPixelStride >= 1) { "bad strides $yRowStride, $uvPixelStride" }
        val out = ByteArray(width * height * 3 / 2)
        val ys = y.duplicate()
        for (row in 0 until height) {
            ys.position(row * yRowStride)
            ys.get(out, row * width, width)
        }
        val cw = width / 2
        val rowLen = (cw - 1) * uvPixelStride + 1
        val uRow = ByteArray(rowLen)
        val vRow = ByteArray(rowLen)
        val us = u.duplicate()
        val vs = v.duplicate()
        var o = width * height
        for (row in 0 until height / 2) {
            us.position(row * uvRowStride)
            us.get(uRow, 0, rowLen)
            vs.position(row * uvRowStride)
            vs.get(vRow, 0, rowLen)
            var i = 0
            repeat(cw) {
                out[o++] = vRow[i]
                out[o++] = uRow[i]
                i += uvPixelStride
            }
        }
        return out
    }

    /** NV21 of an open YUV_420_888 [image] (an odd last row or column is cut). */
    fun of(image: Image): KeptPixels.Nv21 {
        val p = image.planes
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        val bytes = copy(w, h, p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride)
        return KeptPixels.Nv21(w, h, bytes)
    }
}
