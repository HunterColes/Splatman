package com.huntercoles.splatman.capture.processing

import android.graphics.Bitmap
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.still.SplatStill
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

/** Encodes ARGB pixels (row-major, `width x height`) as a PNG into [out]; Bitmap in the app, a fake in JVM tests. */
fun interface ThumbnailEncoder {
    fun encode(argb: IntArray, width: Int, height: Int, out: OutputStream)
}

/** The app's encoder: android.graphics.Bitmap's PNG compressor. */
class BitmapThumbnailEncoder @Inject constructor() : ThumbnailEncoder {
    override fun encode(argb: IntArray, width: Int, height: Int, out: OutputStream) {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) throw IOException("PNG encode failed")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PNG_QUALITY = 100
    }
}

/**
 * Writes a scan's `thumbnail.png` (ticket #175): a [SplatStill] of the finished splat over [background] (ARGB, the
 * viewer's background), to `thumbnail.png.tmp`, synced, then renamed. Never throws: a failure is logged, the temp
 * file removed, and the scan stays as it was.
 */
class ThumbnailWriter(private val encoder: ThumbnailEncoder, private val background: Int) {

    /** Returns whether `thumbnail.png` was written. */
    fun write(cloud: SplatCloud, scan: File): Boolean {
        val temp = File(scan, TEMP_FILE)
        return try {
            val pixels = SplatStill.render(cloud, background = background)
            FileOutputStream(temp).use { file ->
                val out = BufferedOutputStream(file)
                encoder.encode(pixels, SplatStill.DEFAULT_WIDTH, SplatStill.DEFAULT_HEIGHT, out)
                out.flush()
                file.fd.sync()
            }
            if (!temp.renameTo(File(scan, FILE))) throw IOException("cannot rename $TEMP_FILE to $FILE")
            true
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            temp.delete()
            Timber.w(e, "Writing the scan's thumbnail failed")
            false
        } catch (e: OutOfMemoryError) {
            temp.delete()
            Timber.w(e, "Out of memory writing the scan's thumbnail")
            false
        }
    }

    companion object {
        /** The scan's still, a 256 x 320 PNG beside `splat.ply`. */
        const val FILE = "thumbnail.png"
        const val TEMP_FILE = "thumbnail.png.tmp"
    }
}
