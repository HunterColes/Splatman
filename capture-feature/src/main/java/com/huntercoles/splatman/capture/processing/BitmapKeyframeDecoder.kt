package com.huntercoles.splatman.capture.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.huntercoles.splatman.splat.model.RgbImage
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** [KeyframeDecoder] on `BitmapFactory`: subsampled JPEG decode, then a filtered scale to the exact training size. */
class BitmapKeyframeDecoder @Inject constructor() : KeyframeDecoder {
    override fun decode(file: File, longSide: Int): RgbImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("cannot decode ${file.name}")
        val (w, h) = trainingSize(bounds.outWidth, bounds.outHeight, longSide)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("cannot decode ${file.name}")
        val scaled = if (decoded.width == w && decoded.height == h) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()
        return RgbImage(w, h, argbToRgba(pixels))
    }
}
