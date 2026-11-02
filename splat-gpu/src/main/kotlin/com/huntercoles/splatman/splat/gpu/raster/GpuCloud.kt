package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.model.SplatCloud

/**
 * A [SplatCloud] on the GPU: one fp32 SSBO holding the cloud's arrays struct-of-arrays, each in [SplatCloud]'s own
 * layout, one section after the other (see [CloudLayout]). One buffer, not five, so a kernel that reads the cloud
 * spends one SSBO binding (the projection also writes six, and the probe only guarantees eight). Made and used on
 * the GL thread ([GlesCompute.run]). [upload] reuses the buffer and grows it only when a cloud has more splats than
 * [capacity].
 */
class GpuCloud(gl: GlesCompute) : AutoCloseable {
    /** Splats of the last [upload]. */
    var count: Int = 0
        private set

    /** Splats the buffer has room for; the section offsets are multiples of it. */
    var capacity: Int = 1
        private set

    val buffer: Ssbo = gl.ssbo(CloudLayout.bytes(capacity))

    /** Writes [cloud]'s positions, log-scales, rotations, opacity logits and degree-0 SH; higher SH bands are unused. */
    fun upload(cloud: SplatCloud) {
        val cap = Capacity.grow(capacity, cloud.count)
        if (cap != capacity) {
            buffer.resize(CloudLayout.bytes(cap))
            capacity = cap
        }
        count = cloud.count
        if (cloud.count == 0) return
        buffer.upload(cloud.positions, CloudLayout.byteOffset(CloudLayout.POSITIONS, cap))
        buffer.upload(cloud.logScales, CloudLayout.byteOffset(CloudLayout.LOG_SCALES, cap))
        buffer.upload(cloud.rotations, CloudLayout.byteOffset(CloudLayout.ROTATIONS, cap))
        buffer.upload(cloud.opacityLogits, CloudLayout.byteOffset(CloudLayout.OPACITY_LOGITS, cap))
        buffer.upload(cloud.shDc, CloudLayout.byteOffset(CloudLayout.SH_DC, cap))
    }

    override fun close() = buffer.close()
}

/**
 * The float layout of [GpuCloud.buffer] for a capacity C: section k starts at float `k * C` and holds the
 * [SplatCloud] array of the same name, unchanged (positions 3C, log-scales 3C, rotations 4C, opacity logits C,
 * degree-0 SH 3C). The projection shader reads the same offsets.
 */
object CloudLayout {
    /** Section starts, in units of the capacity. */
    const val POSITIONS = 0
    const val LOG_SCALES = 3
    const val ROTATIONS = 6
    const val OPACITY_LOGITS = 10
    const val SH_DC = 11

    /** Floats per splat of capacity. */
    const val FLOATS_PER_SPLAT = 14

    /** First float of [section] for [capacity]. */
    fun floatOffset(section: Int, capacity: Int): Int {
        require(section in 0 until FLOATS_PER_SPLAT && capacity >= 0) { "section $section, capacity $capacity" }
        return Math.multiplyExact(section, capacity)
    }

    fun byteOffset(section: Int, capacity: Int): Int =
        Math.multiplyExact(floatOffset(section, capacity), Std430.SCALAR_BYTES)

    /** Buffer size for [capacity] splats (at least one: an SSBO is never empty). */
    fun bytes(capacity: Int): Int {
        require(capacity >= 0) { "capacity must be >= 0, was $capacity" }
        return Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(FLOATS_PER_SPLAT, maxOf(1, capacity)))
    }
}

/** Grow-only sizing shared by the GPU buffers: nothing is reallocated while the size needed fits. */
internal object Capacity {
    /** [current] when [needed] fits, else at least double (capped at Int.MAX_VALUE), so growth is amortised. */
    fun grow(current: Int, needed: Int): Int {
        require(current >= 0 && needed >= 0) { "current $current, needed $needed" }
        if (needed <= current) return current
        val doubled = if (current > Int.MAX_VALUE / 2) Int.MAX_VALUE else current * 2
        return maxOf(needed, doubled)
    }
}
