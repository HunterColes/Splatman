package com.huntercoles.splatman.splat.gpu.optim

import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.RgbImage

/**
 * Every keyframe image on the GPU, uploaded once at construction as packed RGBA8 `uint`s (ADR 0010): one SSBO per
 * image, one `uint` per pixel, r in the low byte ([pack]). Made and used on the GL thread; [close] frees them.
 */
class GpuTargets(gl: GlesCompute, images: List<RgbImage>) : AutoCloseable {

    /** One uploaded image: [buffer] holds [pixels] packed `uint`s, row-major from the top-left. */
    class Target internal constructor(val buffer: Ssbo, val width: Int, val height: Int) {
        val pixels: Int get() = width * height

        /** RGB floats the loss compares: 3 per pixel. */
        val channels: Int get() = 3 * pixels
    }

    private val targets: List<Target> = ArrayList<Target>(images.size).also { out ->
        try {
            for (img in images) {
                val buf = gl.ssbo(img.width * img.height * BYTES_PER_PIXEL)
                out += Target(buf, img.width, img.height)
                buf.upload(pack(img.rgba))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            out.forEach { it.buffer.close() }
            throw e
        }
    }

    val size: Int get() = targets.size

    /** The target of keyframe [index]. */
    operator fun get(index: Int): Target = targets[index]

    override fun close() {
        targets.forEach { it.buffer.close() }
    }

    companion object {
        const val BYTES_PER_PIXEL = 4

        /** Uploads the image of every keyframe, in order. GL thread only. */
        fun of(gl: GlesCompute, keyframes: List<Keyframe>) = GpuTargets(gl, keyframes.map { it.image })

        /** RGBA8888 bytes to one `uint` per pixel: `r | g << 8 | b << 16 | a << 24`. */
        fun pack(rgba: ByteArray): IntArray {
            require(rgba.size % BYTES_PER_PIXEL == 0) { "rgba must hold 4 bytes per pixel, got ${rgba.size}" }
            return IntArray(rgba.size / BYTES_PER_PIXEL) { p ->
                val o = BYTES_PER_PIXEL * p
                (rgba[o].toInt() and BYTE) or
                    ((rgba[o + 1].toInt() and BYTE) shl 8) or
                    ((rgba[o + 2].toInt() and BYTE) shl 16) or
                    ((rgba[o + 3].toInt() and BYTE) shl 24)
            }
        }

        private const val BYTE = 0xFF
    }
}
