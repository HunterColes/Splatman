package com.huntercoles.splatman.library.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.File

/**
 * Decodes a scan's `thumbnail.png` at card size: the bounds first, then the pixels with an `inSampleSize` that keeps
 * the bitmap no smaller than the card, so a huge file never decodes at full size. A missing, empty or corrupt file
 * gives null (the card keeps its plain block). Blocking; call it off the main thread.
 */
object ThumbnailDecoder {

    fun decode(file: File, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0 || !file.isFile || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Timber.w(e, "Thumbnail %s did not decode", file.name)
            null
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "Thumbnail %s is too big to decode", file.name)
            null
        }
    }

    /**
     * The largest power of two that, dividing a [width] x [height] image, still leaves it at least [reqWidth] x
     * [reqHeight] (1 when the image is already that small or the request is empty).
     */
    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var size = 1
        while (width / (size * 2) >= reqWidth && height / (size * 2) >= reqHeight) size *= 2
        return size
    }
}
