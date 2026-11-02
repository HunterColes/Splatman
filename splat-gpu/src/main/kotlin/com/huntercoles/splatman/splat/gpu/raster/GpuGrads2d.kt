package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type

/**
 * Stages 1 and 2 of the GPU backward pass (ADR 0010; `RasterizerBackward` stages 1 and 2): from a [GpuFrame] a
 * forward pass wrote and dL/dColor on the GPU, [run] writes [entryGrads] (the per tile-entry partials,
 * `SplatGrads.entryGrads`) and [splatGrads2d] (their per-splat sums, `SplatGrads.splatGrads2d` in fp32), both in
 * [Grads2dLayout]. Nothing is read back; [readSplatGrads2d] is for tests. No float or int atomics on global memory:
 * the result is deterministic. Any thread may call; the GL work runs on [gl]'s thread. The buffers only grow; [close]
 * frees them and the programs.
 */
class GpuGrads2d(private val gl: GlesCompute) : AutoCloseable {
    private val packProgram: ComputeProgram = gl.run { program(BackwardTileShaders.PACK) }
    private val replayProgram: ComputeProgram = gl.run { program(BackwardTileShaders.REPLAY) }
    private val gatherProgram: ComputeProgram = gl.run { program(BackwardTileShaders.GATHER) }

    /** [Grads2dLayout.SPLAT] floats per splat: the replay's per-splat inputs in one binding. */
    private val packed: Ssbo = gl.run { ssbo(floatBytes(Grads2dLayout.SPLAT)) }
    private var splatCapacity = 1

    /** [Grads2dLayout.ENTRY] floats per tile-list entry of the last [run]. */
    val entryGrads: Ssbo = gl.run { ssbo(floatBytes(Grads2dLayout.ENTRY)) }
    private var entryCapacity = 1

    /** [Grads2dLayout.ENTRY] floats per splat of the last [run]: dL / d u, v, conic a, b, c, colour r, g, b, opacity. */
    val splatGrads2d: Ssbo = gl.run { ssbo(floatBytes(Grads2dLayout.ENTRY)) }

    /** Splats of the last [run]. */
    var splatCount: Int = 0
        private set

    /**
     * Replays every tile of [frame] (the last forward pass) back to front against [dLdColor] (3 floats per pixel, as
     * `RenderBuffers.color`; `GpuLoss` writes it), then gathers per splat. Overwrites the first
     * `frame.render.splatCount` records of [splatGrads2d].
     */
    fun run(frame: GpuFrame, dLdColor: Ssbo) {
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, Math.multiplyExact(3, frame.pixels))
        require(dLdColor.sizeBytes >= bytes) { "dLdColor holds ${dLdColor.sizeBytes} bytes, needs $bytes" }
        gl.run {
            val r = frame.render
            val n = r.splatCount
            val entries = r.tileEntries
            ensure(n, entries)
            splatCount = n
            if (n == 0) return@run

            packProgram.setUInt("n", n)
            r.means2d.bind(0)
            r.conics.bind(1)
            r.colors.bind(2)
            r.opacities.bind(3)
            packed.bind(4)
            packProgram.dispatchFor(n, BackwardTileShaders.LOCAL)

            if (entries > 0) {
                val (gx, gy) = BackwardTileShaders.replayGroups(frame.width, frame.height)
                replayProgram.setUInt("tilesX", r.tilesX)
                replayProgram.setUInt("width", frame.width)
                replayProgram.setUInt("height", frame.height)
                r.tileStart.bind(0)
                r.tileSplats.bind(1)
                packed.bind(2)
                frame.image.bind(3)
                frame.contributors.bind(4)
                dLdColor.bind(5)
                entryGrads.bind(6)
                replayProgram.dispatch(gx, gy)
            }

            gatherProgram.setUInt("n", n)
            gatherProgram.setInt("tilesX", r.tilesX)
            gatherProgram.setInt("tilesY", r.tilesY)
            r.radii.bind(0)
            r.means2d.bind(1)
            r.depths.bind(2)
            r.tileStart.bind(3)
            r.tileSplats.bind(4)
            entryGrads.bind(5)
            splatGrads2d.bind(6)
            gatherProgram.dispatchFor(n, BackwardTileShaders.LOCAL)
        }
    }

    /** The first [splatCount] records of [splatGrads2d]: a GPU -> host read-back, tests only. */
    fun readSplatGrads2d(): FloatArray = gl.run {
        if (splatCount == 0) FloatArray(0) else splatGrads2d.downloadFloats(Grads2dLayout.ENTRY * splatCount)
    }

    private fun ensure(splats: Int, entries: Int) {
        val sc = Capacity.grow(splatCapacity, splats)
        if (sc != splatCapacity) {
            packed.resize(floatBytes(Math.multiplyExact(Grads2dLayout.SPLAT, sc)))
            splatGrads2d.resize(floatBytes(Math.multiplyExact(Grads2dLayout.ENTRY, sc)))
            splatCapacity = sc
        }
        val ec = Capacity.grow(entryCapacity, entries)
        if (ec != entryCapacity) {
            entryGrads.resize(floatBytes(Math.multiplyExact(Grads2dLayout.ENTRY, ec)))
            entryCapacity = ec
        }
    }

    override fun close() = gl.run {
        listOf(packProgram, replayProgram, gatherProgram).forEach { it.close() }
        listOf(packed, entryGrads, splatGrads2d).forEach { it.close() }
    }

    private companion object {
        fun floatBytes(n: Int) = Std430.arrayBytes(Std430Type.FLOAT, n)
    }
}
