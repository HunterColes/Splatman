package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.train.raster.SplatGrads

/**
 * What one [GpuRasterizer.backward] writes, GPU-resident: the gradient of every raw parameter of the cloud and the
 * view-gradient norms, `SplatGrads`' arrays in one fp32 SSBO ([GradsLayout], the [CloudLayout] sections plus one).
 * Every backward call overwrites the first [count] splats of every section (culled splats get 0); nothing is read
 * back. [readInto] is for tests only. Any thread may make, read or close one: the GL work runs on [gl]'s thread. The
 * buffer only grows.
 */
class GpuGrads(private val gl: GlesCompute) : AutoCloseable {
    /** Splats of the last backward call. */
    var count: Int = 0
        private set

    /** Splats the buffer has room for; the section offsets are multiples of it. */
    var capacity: Int = 1
        private set

    val buffer: Ssbo = gl.run { ssbo(GradsLayout.bytes(capacity)) }

    /** Sizes the buffer for [n] splats (GL thread; [GpuRasterizer.backward] calls it). */
    internal fun ensure(n: Int) {
        val cap = Capacity.grow(capacity, n)
        if (cap != capacity) {
            buffer.resize(GradsLayout.bytes(cap))
            capacity = cap
        }
        count = n
    }

    /**
     * Copies the last backward call into [out]: positions, log-scales, rotations, opacity logits, SH dc and
     * viewGradNorms, sized to [count]. The lens gradients are not computed on the GPU (0). A GPU -> host read-back:
     * tests only.
     */
    fun readInto(out: SplatGrads) {
        out.ensureSplats(count)
        if (count == 0) return
        val floats = gl.run { buffer.downloadFloats(GradsLayout.FLOATS_PER_SPLAT * capacity) }
        fun copy(section: Int, width: Int, into: FloatArray) {
            val at = GradsLayout.floatOffset(section, capacity)
            floats.copyInto(into, 0, at, at + width * count)
        }
        copy(GradsLayout.POSITIONS, 3, out.positions)
        copy(GradsLayout.LOG_SCALES, 3, out.logScales)
        copy(GradsLayout.ROTATIONS, 4, out.rotations)
        copy(GradsLayout.OPACITY_LOGITS, 1, out.opacityLogits)
        copy(GradsLayout.SH_DC, 3, out.shDc)
        copy(GradsLayout.VIEW_GRAD_NORMS, 1, out.viewGradNorms)
    }

    override fun close() = gl.run { buffer.close() }
}

/**
 * The float layout of [GpuGrads.buffer] for a capacity C: the [CloudLayout] sections at the same offsets (so a
 * cloud section and its gradient line up for Adam), each in `SplatGrads`' layout, then viewGradNorms (C floats).
 */
object GradsLayout {
    const val POSITIONS = CloudLayout.POSITIONS
    const val LOG_SCALES = CloudLayout.LOG_SCALES
    const val ROTATIONS = CloudLayout.ROTATIONS
    const val OPACITY_LOGITS = CloudLayout.OPACITY_LOGITS
    const val SH_DC = CloudLayout.SH_DC
    const val VIEW_GRAD_NORMS = CloudLayout.FLOATS_PER_SPLAT

    /** Floats per splat of capacity. */
    const val FLOATS_PER_SPLAT = VIEW_GRAD_NORMS + 1

    /** First float of [section] for [capacity]. */
    fun floatOffset(section: Int, capacity: Int): Int {
        require(section in 0 until FLOATS_PER_SPLAT && capacity >= 0) { "section $section, capacity $capacity" }
        return Math.multiplyExact(section, capacity)
    }

    /** Buffer size for [capacity] splats (at least one: an SSBO is never empty). */
    fun bytes(capacity: Int): Int {
        require(capacity >= 0) { "capacity must be >= 0, was $capacity" }
        return Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(FLOATS_PER_SPLAT, maxOf(1, capacity)))
    }
}
