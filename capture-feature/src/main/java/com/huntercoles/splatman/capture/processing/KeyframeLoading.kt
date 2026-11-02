package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.io.bundle.BundleFrame
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.RgbImage
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes a keyframe's image file to RGBA, downscaled so its long side is at most `longSide` pixels. */
fun interface KeyframeDecoder {
    @Throws(IOException::class)
    fun decode(file: File, longSide: Int): RgbImage
}

/** Training size for a `width x height` image: long side `longSide`, aspect kept, never upscaled, pixels. */
fun trainingSize(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && longSide > 0) { "sizes must be positive" }
    val long = max(width, height)
    if (long <= longSide) return width to height
    val s = longSide.toDouble() / long
    return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
}

/** Largest power-of-two JPEG subsample that still leaves the image at least `targetWidth x targetHeight`. */
fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    var n = 1
    while (width / (2 * n) >= targetWidth && height / (2 * n) >= targetHeight) n *= 2
    return n
}

/** Packed `0xAARRGGBB` pixels (Android `Bitmap.getPixels`) to RGBA8888 bytes. */
fun argbToRgba(pixels: IntArray): ByteArray {
    val out = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        out[4 * i] = (p shr 16).toByte()
        out[4 * i + 1] = (p shr 8).toByte()
        out[4 * i + 2] = p.toByte()
        out[4 * i + 3] = (p ushr 24).toByte()
    }
    return out
}

/** A bundle frame as a training [Keyframe]: image decoded at training size, intrinsics scaled to match it. */
@Throws(IOException::class)
fun loadKeyframe(id: Int, frame: BundleFrame, decoder: KeyframeDecoder, longSide: Int): Keyframe {
    val image = decoder.decode(frame.imageFile, longSide)
    return Keyframe(
        id = id,
        timestampNs = frame.timestampNs,
        intrinsics = frame.intrinsics.scaled(image.width, image.height),
        pose = frame.pose,
        image = image,
        depth = CaptureBundleReader.readDepth(frame),
    )
}
