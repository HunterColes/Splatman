package com.huntercoles.splatman.capture.recording

import android.graphics.Bitmap
import com.huntercoles.splatman.splat.model.RgbImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

/** Encodes an RGBA image as JPEG (ADR 0007: keyframes are JPEG q90, sensor orientation). */
fun interface JpegEncoder {
    /** The JPEG bytes of [image] at [quality] (0..100). */
    fun encode(image: RgbImage, quality: Int): ByteArray
}

/** [JpegEncoder] on `Bitmap.compress`. */
class BitmapJpegEncoder @Inject constructor() : JpegEncoder {
    override fun encode(image: RgbImage, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val px = image.rgba
        val argb = IntArray(w * h) { i ->
            val o = 4 * i
            (0xFF shl 24) or ((px[o].toInt() and 0xFF) shl 16) or ((px[o + 1].toInt() and 0xFF) shl 8) or
                (px[o + 2].toInt() and 0xFF)
        }
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream(w * h / 4)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("JPEG encoding failed")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
