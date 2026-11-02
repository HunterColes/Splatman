package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens

/**
 * The GPU forward pass (ADR 0010), with the contract of `Rasterizer.forward`: project ([GpuBinning]), bin into
 * front-to-back tile lists, then composite every tile ([CompositeShaders]) into a [GpuFrame]. [backward] is
 * `Rasterizer.backward` (pinhole): tile replay and per-splat gather ([GpuGrads2d]), then the per-splat chain rule
 * ([BackwardSplatShaders]) into a [GpuGrads]. The CPU `Rasterizer` stays the golden renderer and the fallback. The
 * results stay on the GPU: a pass reads nothing back but the 4-byte entry total that sizes the sort. Any thread may
 * call; the GL work runs on [compute]'s thread. [close] frees the programs and scratch buffers (not the frames or
 * the gradients).
 */
class GpuRasterizer(private val compute: GlesCompute) : AutoCloseable {
    private val binning: GpuBinning = compute.run { GpuBinning(this) }
    private val compositeProgram: ComputeProgram = compute.run { program(CompositeShaders.COMPOSITE) }

    /** The upload target of the [SplatCloud] overloads, made on first use. */
    private var uploaded: GpuCloud? = null

    /** Backward stages 1 and 2 and the stage-3 program, made on the first [backward]. */
    private var grads2d: GpuGrads2d? = null
    private var splatProgram: ComputeProgram? = null

    /**
     * Renders [cloud] seen by a camera with [intrinsics] at [pose] (`T_W_Cgl`, ADR 0003) into [out], whose size must
     * match the intrinsics. Overwrites every output of [out]. [lens] as in `Rasterizer.forward` (null = pinhole).
     */
    fun forward(cloud: GpuCloud, intrinsics: CameraIntrinsics, pose: CameraPose, out: GpuFrame, lens: Lens? = null) {
        require(out.width == intrinsics.width && out.height == intrinsics.height) {
            "frame ${out.width}x${out.height} does not match intrinsics ${intrinsics.width}x${intrinsics.height}"
        }
        compute.run {
            binning.forward(cloud, intrinsics, pose, out.render, lens)
            composite(out)
        }
    }

    /** [forward] of a host [cloud]: uploads it into a buffer this rasteriser keeps (and grows), then renders. */
    fun forward(cloud: SplatCloud, intrinsics: CameraIntrinsics, pose: CameraPose, out: GpuFrame, lens: Lens? = null) {
        compute.run {
            val gpuCloud = uploaded ?: GpuCloud(this).also { uploaded = it }
            gpuCloud.upload(cloud)
            forward(gpuCloud, intrinsics, pose, out, lens)
        }
    }

    /**
     * `Rasterizer.backward` without a lens: from [frame] (the last [forward] of [cloud] with [intrinsics] at [pose],
     * pinhole) and [dLdColor] (3 floats per pixel, as `RenderBuffers.color`; `GpuLoss` writes it), writes the
     * gradient of every raw parameter and the view-gradient norms into [out] ([GpuGrads], first `cloud.count` splats).
     * Nothing is read back. A lens forward pass is not differentiated here (the GPU backend keeps the pinholes).
     */
    @Suppress("LongParameterList")
    fun backward(
        cloud: GpuCloud, intrinsics: CameraIntrinsics, pose: CameraPose, frame: GpuFrame, dLdColor: Ssbo, out: GpuGrads,
    ) {
        require(frame.width == intrinsics.width && frame.height == intrinsics.height) {
            "frame ${frame.width}x${frame.height} does not match intrinsics ${intrinsics.width}x${intrinsics.height}"
        }
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(3, frame.pixels))
        require(dLdColor.sizeBytes >= bytes) { "dLdColor holds ${dLdColor.sizeBytes} bytes, needs $bytes" }
        compute.run {
            val r = frame.render
            require(r.splatCount == cloud.count) {
                "the frame holds a forward pass of ${r.splatCount} splats, the cloud has ${cloud.count}"
            }
            val stage = grads2d ?: GpuGrads2d(this).also { grads2d = it }
            val program = splatProgram ?: program(BackwardSplatShaders.SPLAT).also { splatProgram = it }
            stage.run(frame, dLdColor)
            val n = cloud.count
            out.ensure(n)
            if (n == 0) return@run
            val view = FrameConversions.glToCv(pose)
            val w = view.rotation.normalized().toMat3().transpose().values
            program.setUInt("n", n)
            program.setUInt("capacity", cloud.capacity)
            program.setUInt("outCapacity", out.capacity)
            for (k in 0 until 9) program.setFloat(ROT[k], w[k])
            program.setFloat("camX", view.translationM.x)
            program.setFloat("camY", view.translationM.y)
            program.setFloat("camZ", view.translationM.z)
            program.setFloat("fx", intrinsics.fx)
            program.setFloat("fy", intrinsics.fy)
            program.setFloat("halfW", frame.width / 2f)
            program.setFloat("halfH", frame.height / 2f)
            cloud.buffer.bind(0)
            r.radii.bind(1)
            stage.splatGrads2d.bind(2)
            out.buffer.bind(3)
            program.dispatchFor(n, BackwardSplatShaders.LOCAL)
        }
    }

    /** [backward] of a host [cloud]: uploads it into the buffer the [SplatCloud] [forward] uses, then differentiates. */
    @Suppress("LongParameterList")
    fun backward(
        cloud: SplatCloud, intrinsics: CameraIntrinsics, pose: CameraPose, frame: GpuFrame, dLdColor: Ssbo, out: GpuGrads,
    ) {
        compute.run {
            val gpuCloud = uploaded ?: GpuCloud(this).also { uploaded = it }
            gpuCloud.upload(cloud)
            backward(gpuCloud, intrinsics, pose, frame, dLdColor, out)
        }
    }

    /** `Rasterizer.compositeTile` over every tile of [out], from the lists in [GpuFrame.render]. GL thread. */
    private fun composite(out: GpuFrame) {
        val r = out.render
        val (gx, gy) = CompositeShaders.groups(out.width, out.height)
        compositeProgram.setUInt("tilesX", r.tilesX)
        compositeProgram.setUInt("width", out.width)
        compositeProgram.setUInt("height", out.height)
        r.tileStart.bind(0)
        r.tileSplats.bind(1)
        r.means2d.bind(2)
        r.conics.bind(3)
        r.colors.bind(4)
        r.opacities.bind(5)
        out.image.bind(6)
        out.contributors.bind(7)
        compositeProgram.dispatch(gx, gy)
    }

    override fun close() = compute.run {
        uploaded?.close()
        uploaded = null
        binning.close()
        compositeProgram.close()
        grads2d?.close()
        grads2d = null
        splatProgram?.close()
        splatProgram = null
    }

    private companion object {
        val ROT = Array(9) { "r$it" }
    }
}
