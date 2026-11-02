package com.huntercoles.splatman.splat.gpu.optim

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.Dispatch
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type

/**
 * The L1 loss against a GPU-resident target, as `CpuBackend.l1` computes it ([LossShaders]). Made and used on the GL
 * thread. [compute] runs both passes and fills dL/dColor on the GPU; [loss] downloads the one float of the sum.
 */
class GpuLoss(gl: GlesCompute) : AutoCloseable {
    private val partialsProgram: ComputeProgram = gl.program(LossShaders.PARTIALS)
    private val totalProgram: ComputeProgram = gl.program(LossShaders.TOTAL)
    private val lut: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.FLOAT, LUT_SIZE)).apply { upload(byteLut()) }
    private var partials: Ssbo = gl.ssbo(Std430.SCALAR_BYTES)
    private val total: Ssbo = gl.ssbo(Std430.SCALAR_BYTES)
    private var lastSize = 0

    /**
     * Mean |rendered - target| of [rendered] (3 floats per pixel of [target]) into the sum buffer, and
     * sign(rendered - target) / size into [dLdColor]. Read the loss with [loss].
     */
    fun compute(rendered: Ssbo, target: GpuTargets.Target, dLdColor: Ssbo) {
        val size = target.channels
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, size)
        require(rendered.sizeBytes >= bytes) { "rendered holds ${rendered.sizeBytes} bytes, needs $bytes" }
        require(dLdColor.sizeBytes >= bytes) { "dLdColor holds ${dLdColor.sizeBytes} bytes, needs $bytes" }
        val groups = partialGroups(size)
        val partialBytes = Std430.arrayBytes(Std430Type.FLOAT, groups)
        if (partials.sizeBytes < partialBytes) partials.resize(partialBytes)

        rendered.bind(LossShaders.B_RENDERED)
        target.buffer.bind(LossShaders.B_TARGET)
        lut.bind(LossShaders.B_LUT)
        dLdColor.bind(LossShaders.B_DLDCOLOR)
        partials.bind(LossShaders.B_PARTIALS)
        total.bind(LossShaders.B_TOTAL)

        partialsProgram.setUInt("size", size)
        partialsProgram.setFloat("scale", gradScale(size))
        partialsProgram.dispatch(groups)
        totalProgram.setUInt("count", groups)
        totalProgram.dispatch(1)
        lastSize = size
    }

    /** The loss of the last [compute]: the GPU's float sum, divided by the size in Double as the CPU does. */
    fun loss(): Float {
        check(lastSize > 0) { "no loss computed yet" }
        return meanOf(total.downloadFloats(1)[0], lastSize)
    }

    override fun close() {
        partialsProgram.close()
        totalProgram.close()
        lut.close()
        partials.close()
        total.close()
    }

    companion object {
        const val LUT_SIZE = 256

        /** Workgroups of the first pass for [size] channels. */
        fun partialGroups(size: Int): Int {
            require(size > 0) { "size must be > 0, was $size" }
            return Dispatch.groups(size, LossShaders.LOCAL)
        }

        /** |dL/dColor| for [size] channels, the CPU's `1f / size`. */
        fun gradScale(size: Int): Float = 1f / size

        /** The CPU's `(sum / size).toFloat()` from the GPU's float sum. */
        fun meanOf(sum: Float, size: Int): Float = (sum.toDouble() / size).toFloat()

        /** Byte value to target float, the CPU's `(b and 0xFF) / 255f`. */
        fun byteLut(): FloatArray = FloatArray(LUT_SIZE) { it / 255f }
    }
}
