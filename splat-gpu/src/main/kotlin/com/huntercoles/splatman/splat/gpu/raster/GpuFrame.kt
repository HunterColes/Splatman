package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.train.raster.RenderBuffers

/**
 * What one [GpuRasterizer.forward] writes, GPU-resident: the GPU side of `RenderBuffers` for one image size. [render]
 * holds the projection and the tile lists; [image] the colour, alpha and finalT ([FrameLayout] sections, fp32) and
 * [contributors] the per-pixel contributor counts (int), each with `RenderBuffers`' meaning. Nothing is read back
 * by a forward pass; [readInto] is for tests and snapshots only. Any thread may make, read or close one: the GL work
 * runs on [gl]'s thread.
 */
class GpuFrame(private val gl: GlesCompute, val width: Int, val height: Int) : AutoCloseable {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
    }

    /** Pixels, `width * height`. */
    val pixels: Int = Math.multiplyExact(width, height)

    /** Per-splat projection and tile lists of the last forward pass. */
    val render: GpuRenderBuffers = gl.run { GpuRenderBuffers(this, width, height) }

    /** [FrameLayout.FLOATS_PER_PIXEL] floats per pixel, in [FrameLayout] sections. */
    val image: Ssbo = gl.run { ssbo(FrameLayout.imageBytes(pixels)) }

    /** One int per pixel: `RenderBuffers.contributors`. */
    val contributors: Ssbo = gl.run { ssbo(Std430.arrayBytes(Std430Type.INT, pixels)) }

    /**
     * Copies the per-pixel outputs (color, alpha, finalT, contributors) into [out], which must be this size; its
     * projection and tile lists are left alone. A GPU -> host read-back: tests and snapshots only.
     */
    fun readInto(out: RenderBuffers) {
        require(out.width == width && out.height == height) {
            "buffers ${out.width}x${out.height} do not match the frame ${width}x$height"
        }
        gl.run {
            val floats = image.downloadFloats(FrameLayout.FLOATS_PER_PIXEL * pixels)
            val alphaAt = FrameLayout.offset(FrameLayout.ALPHA, pixels)
            val finalTAt = FrameLayout.offset(FrameLayout.FINAL_T, pixels)
            floats.copyInto(out.color, 0, FrameLayout.offset(FrameLayout.COLOR, pixels), alphaAt)
            floats.copyInto(out.alpha, 0, alphaAt, finalTAt)
            floats.copyInto(out.finalT, 0, finalTAt, FrameLayout.offset(FrameLayout.FLOATS_PER_PIXEL, pixels))
            contributors.downloadInts(pixels).copyInto(out.contributors)
        }
    }

    override fun close() = gl.run {
        render.close()
        image.close()
        contributors.close()
    }
}

/**
 * The float layout of [GpuFrame.image] for P pixels: section k starts at float `k * P`. Colour holds 3 floats per
 * pixel (r, g, b, as `RenderBuffers.color`), alpha and finalT one each. The compositing shader writes these offsets.
 */
object FrameLayout {
    /** Section starts, in units of the pixel count. */
    const val COLOR = 0
    const val ALPHA = 3
    const val FINAL_T = 4

    /** Floats per pixel. */
    const val FLOATS_PER_PIXEL = 5

    /** First float of the section starting at [section] P for [pixels] pixels (FLOATS_PER_PIXEL is the end). */
    fun offset(section: Int, pixels: Int): Int {
        require(section in 0..FLOATS_PER_PIXEL && pixels >= 0) { "section $section, pixels $pixels" }
        return Math.multiplyExact(section, pixels)
    }

    /** Bytes of the image buffer for [pixels] pixels. */
    fun imageBytes(pixels: Int): Int {
        require(pixels > 0) { "pixels must be > 0, was $pixels" }
        return Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(FLOATS_PER_PIXEL, pixels))
    }
}
