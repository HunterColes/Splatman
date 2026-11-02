package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer

/**
 * The GPU side of `RenderBuffers` for one image size: the per-splat projection (same arrays, same layout: radii
 * int, depths, means2d 2 floats, conics 3 floats, colours 3 floats, opacities) and the tile lists ([tileStart],
 * [tileSplats], the same meaning as on the CPU). Made and used on the GL thread; the buffers only grow.
 */
class GpuRenderBuffers(gl: GlesCompute, val width: Int, val height: Int) : AutoCloseable {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
    }

    val tilesX: Int = (width + Rasterizer.TILE - 1) / Rasterizer.TILE
    val tilesY: Int = (height + Rasterizer.TILE - 1) / Rasterizer.TILE
    val tileCount: Int = tilesX * tilesY

    /** Splats of the last forward pass. */
    var splatCount: Int = 0
        internal set

    /** Splats the per-splat buffers hold. */
    var splatCapacity: Int = 1
        private set

    /** Entries of the last tile lists: `tileStart[tileCount]`. */
    var tileEntries: Int = 0
        internal set

    /** Entries [tileSplats] holds. */
    var entryCapacity: Int = 1
        private set

    val radii: Ssbo = gl.ssbo(splatBytes(1, 1))
    val depths: Ssbo = gl.ssbo(splatBytes(1, 1))
    val means2d: Ssbo = gl.ssbo(splatBytes(1, 2))
    val conics: Ssbo = gl.ssbo(splatBytes(1, 3))
    val colors: Ssbo = gl.ssbo(splatBytes(1, 3))
    val opacities: Ssbo = gl.ssbo(splatBytes(1, 1))

    /** tileCount + 1 ints: tile t's list is `tileSplats[tileStart[t] until tileStart[t + 1]]`. */
    val tileStart: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.INT, tileCount + 1))

    /** Splat indices of every tile's list, front to back, concatenated in tile order. */
    val tileSplats: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UINT, 1))

    private val perSplat = listOf(radii to 1, depths to 1, means2d to 2, conics to 3, colors to 3, opacities to 1)

    internal fun ensureSplats(n: Int) {
        splatCount = n
        val cap = Capacity.grow(splatCapacity, n)
        if (cap == splatCapacity) return
        for ((buf, floats) in perSplat) buf.resize(splatBytes(cap, floats))
        splatCapacity = cap
    }

    /** Grows [tileSplats]; true when it was reallocated. */
    internal fun ensureEntries(n: Int): Boolean {
        val cap = Capacity.grow(entryCapacity, n)
        if (cap == entryCapacity) return false
        tileSplats.resize(Std430.arrayBytes(Std430Type.UINT, cap))
        entryCapacity = cap
        return true
    }

    override fun close() {
        perSplat.forEach { it.first.close() }
        tileStart.close()
        tileSplats.close()
    }

    private fun splatBytes(n: Int, perSplat: Int) = Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(n, perSplat))
}

/**
 * The first half of the GPU forward pass (ADR 0010): [project] is `Rasterizer.project`, [bin] its `bin`. Binning
 * counts the tiles each splat touches, scans the counts (splat-index order), emits a (tile, depth bits) key per
 * entry, radix-sorts them stably ([GpuRadixSort]) and finds each tile's first entry, so every tile list comes out
 * front to back with ties by index, as on the CPU. One host read-back a pass: the entry total (4 bytes), which sizes
 * the sort. Made and used on the GL thread; the programs are compiled once and the scratch buffers only grow.
 */
class GpuBinning(private val gl: GlesCompute) : AutoCloseable {
    private val projectProgram: ComputeProgram = gl.program(ProjectShaders.PROJECT)
    private val countProgram: ComputeProgram = gl.program(BinShaders.COUNT)
    private val emitProgram: ComputeProgram = gl.program(BinShaders.EMIT)
    private val tileStartProgram: ComputeProgram = gl.program(BinShaders.TILE_START)
    private val scan = GpuScan(gl)
    private val sort = GpuRadixSort(gl, scan)

    /** Counts, then their exclusive scan: n + 1 uints. */
    private val offsets: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UINT, 2))
    private var offsetCapacity = 2
    private val keysA: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UVEC2, 1))
    private val keysB: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UVEC2, 1))
    private val valsB: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UINT, 1))
    private var keyCapacity = 1

    /** Projection and binning of [cloud] seen with [intrinsics] at [pose] (`T_W_Cgl`) into [out]. */
    fun forward(cloud: GpuCloud, intrinsics: CameraIntrinsics, pose: CameraPose, out: GpuRenderBuffers, lens: Lens? = null) {
        project(cloud, intrinsics, pose, out, lens)
        bin(out)
    }

    /** `Rasterizer.project`: writes every per-splat buffer of [out] for [cloud]'s splats. */
    fun project(cloud: GpuCloud, intrinsics: CameraIntrinsics, pose: CameraPose, out: GpuRenderBuffers, lens: Lens? = null) {
        require(out.width == intrinsics.width && out.height == intrinsics.height) {
            "buffers ${out.width}x${out.height} do not match intrinsics ${intrinsics.width}x${intrinsics.height}"
        }
        val l = lens ?: Lens.PINHOLE
        val n = cloud.count
        out.ensureSplats(n)
        val cvPose = FrameConversions.glToCv(pose)
        val r = cvPose.rotation.normalized().toMat3().transpose().values
        val p = projectProgram
        p.setUInt("n", n)
        p.setUInt("capacity", cloud.capacity)
        for (k in 0 until 9) p.setFloat(ROT[k], r[k])
        p.setFloat("camX", cvPose.translationM.x)
        p.setFloat("camY", cvPose.translationM.y)
        p.setFloat("camZ", cvPose.translationM.z)
        p.setFloat("fx", intrinsics.fx * l.focalScale)
        p.setFloat("fy", intrinsics.fy * l.focalScale)
        p.setFloat("cx", intrinsics.cx)
        p.setFloat("cy", intrinsics.cy)
        p.setFloat("k1", l.k1)
        p.setFloat("maxU", (out.width - 1).toFloat())
        p.setFloat("maxV", (out.height - 1).toFloat())
        cloud.buffer.bind(0)
        out.radii.bind(1)
        out.depths.bind(2)
        out.means2d.bind(3)
        out.conics.bind(4)
        out.colors.bind(5)
        out.opacities.bind(6)
        p.dispatchFor(n, ProjectShaders.LOCAL)
    }

    /** `Rasterizer.bin` over the projection in [out]: fills [GpuRenderBuffers.tileStart] and `tileSplats`. */
    fun bin(out: GpuRenderBuffers) {
        val n = out.splatCount
        val cap = Capacity.grow(offsetCapacity, n + 1)
        if (cap != offsetCapacity) {
            offsets.resize(Std430.arrayBytes(Std430Type.UINT, cap))
            offsetCapacity = cap
        }
        countProgram.setUInt("n", n)
        countProgram.setInt("tilesX", out.tilesX)
        countProgram.setInt("tilesY", out.tilesY)
        out.radii.bind(0)
        out.means2d.bind(1)
        offsets.bind(2)
        countProgram.dispatchFor(n + 1, BinShaders.LOCAL)
        scan.scan(offsets, n + 1)
        val total = offsets.downloadInts(1, n * Std430.SCALAR_BYTES)[0]
        require(total in 0..SortKeys.MAX_ENTRIES) { "$total tile entries; the sort takes at most ${SortKeys.MAX_ENTRIES}" }
        out.tileEntries = total
        out.ensureEntries(total)
        ensureKeys(total)

        // Emit where the last of an even or odd number of passes lands the result in out.tileSplats.
        val vals = arrayOf(out.tileSplats, valsB)
        val keys = arrayOf(keysA, keysB)
        val from = SortKeys.passCount(out.tileCount) % 2
        if (total > 0) {
            emitProgram.setUInt("n", n)
            emitProgram.setInt("tilesX", out.tilesX)
            emitProgram.setInt("tilesY", out.tilesY)
            out.radii.bind(0)
            out.means2d.bind(1)
            out.depths.bind(2)
            offsets.bind(3)
            keys[from].bind(4)
            vals[from].bind(5)
            emitProgram.dispatchFor(n, BinShaders.LOCAL)
        }
        val result = sort.sort(total, out.tileCount, keys, vals, from)
        check(result == 0) { "the sorted lists landed in scratch" }

        tileStartProgram.setUInt("total", total)
        tileStartProgram.setUInt("tileCount", out.tileCount)
        keys[0].bind(0)
        out.tileStart.bind(1)
        tileStartProgram.dispatchFor(out.tileCount + 1, BinShaders.LOCAL)
    }

    private fun ensureKeys(n: Int) {
        val cap = Capacity.grow(keyCapacity, n)
        if (cap == keyCapacity) return
        keysA.resize(Std430.arrayBytes(Std430Type.UVEC2, cap))
        keysB.resize(Std430.arrayBytes(Std430Type.UVEC2, cap))
        valsB.resize(Std430.arrayBytes(Std430Type.UINT, cap))
        keyCapacity = cap
    }

    override fun close() {
        listOf(offsets, keysA, keysB, valsB).forEach { it.close() }
        sort.close()
        scan.close()
        listOf(projectProgram, countProgram, emitProgram, tileStartProgram).forEach { it.close() }
    }

    private companion object {
        val ROT = Array(9) { "r$it" }
    }
}
