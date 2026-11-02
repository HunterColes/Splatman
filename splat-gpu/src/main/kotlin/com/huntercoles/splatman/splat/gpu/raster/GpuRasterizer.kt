package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens

/**
 * The GPU forward pass (ADR 0010), with the contract of `Rasterizer.forward`: project ([GpuBinning]), bin into
 * front-to-back tile lists, then composite every tile ([CompositeShaders]) into a [GpuFrame]. The CPU `Rasterizer`
 * stays the golden renderer and the fallback. The results stay on the GPU: a pass reads nothing back but the 4-byte
 * entry total that sizes the sort. Any thread may call; the GL work runs on [compute]'s thread. [close] frees the
 * programs and scratch buffers (not the frames).
 */
class GpuRasterizer(private val compute: GlesCompute) : AutoCloseable {
    private val binning: GpuBinning = compute.run { GpuBinning(this) }
    private val compositeProgram: ComputeProgram = compute.run { program(CompositeShaders.COMPOSITE) }

    /** The upload target of the [SplatCloud] overload, made on first use. */
    private var uploaded: GpuCloud? = null

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
    }
}
