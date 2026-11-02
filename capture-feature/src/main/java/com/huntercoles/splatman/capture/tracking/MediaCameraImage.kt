package com.huntercoles.splatman.capture.tracking

import android.media.Image
import com.huntercoles.splatman.splat.model.RgbImage

/** A YUV_420_888 [Image] from the tracker as a [CameraImage]; [close] releases it (once). */
class MediaCameraImage(private val image: Image) : CameraImage {
    private var closed = false

    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun toRgb(): RgbImage {
        check(!closed) { "camera image already closed" }
        val planes = image.planes
        val strides = Yuv420Strides(
            yRowStride = planes[0].rowStride,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
        )
        val rgba = Yuv420.toRgba(
            y = planes[0].bytes(),
            u = planes[1].bytes(),
            v = planes[2].bytes(),
            strides = strides,
            width = image.width,
            height = image.height,
        )
        return RgbImage(image.width, image.height, rgba)
    }

    override fun luma(): LumaPlane {
        check(!closed) { "camera image already closed" }
        val y = image.planes[0]
        return LumaPlane(image.width, image.height, y.rowStride, y.bytes())
    }

    override fun close() {
        if (closed) return
        closed = true
        image.close()
    }

    private fun Image.Plane.bytes(): ByteArray {
        val src = buffer.duplicate()
        return ByteArray(src.remaining()).also { src.get(it) }
    }
}
