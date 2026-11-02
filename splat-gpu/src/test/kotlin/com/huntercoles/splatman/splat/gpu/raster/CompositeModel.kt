package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import kotlin.math.exp
import kotlin.math.min

/**
 * The [CompositeShaders.COMPOSITE] kernel in plain Kotlin, workgroup by workgroup: 16x16 invocations per tile, the
 * list loaded in batches of [batch] entries (the shader's is [CompositeShaders.BATCH]), the done count taken before
 * each batch and the workgroup leaving once every invocation is done. Reads the projection and tile lists of a CPU
 * [RenderBuffers]; [batchesLoaded] counts the batches every tile actually loaded.
 */
internal class CompositeModel(private val rb: RenderBuffers, private val batch: Int = CompositeShaders.BATCH) {
    private val local = CompositeShaders.LOCAL
    private val invocations = local * local
    val color = FloatArray(3 * rb.width * rb.height)
    val alpha = FloatArray(rb.width * rb.height)
    val finalT = FloatArray(rb.width * rb.height)
    val contributors = IntArray(rb.width * rb.height)
    var batchesLoaded = 0
        private set

    init {
        require(batch in 1..invocations) { "batch $batch" }
        val (gx, gy) = CompositeShaders.groups(rb.width, rb.height)
        for (wy in 0 until gy) for (wx in 0 until gx) workgroup(wx, wy)
    }

    @Suppress("LongMethod", "NestedBlockDepth")
    private fun workgroup(wx: Int, wy: Int) {
        val tile = wy * rb.tilesX + wx
        val begin = rb.tileStart[tile]
        val end = rb.tileStart[tile + 1]
        val xs = IntArray(invocations) { wx * local + it % local }
        val ys = IntArray(invocations) { wy * local + it / local }
        val inside = BooleanArray(invocations) { xs[it] < rb.width && ys[it] < rb.height }
        val tr = FloatArray(invocations) { 1f }
        val r = FloatArray(invocations)
        val g = FloatArray(invocations)
        val b = FloatArray(invocations)
        val last = IntArray(invocations)
        val done = BooleanArray(invocations) { !inside[it] }
        val shared = IntArray(batch)
        var base = begin
        while (base < end) {
            if (done.all { it }) break
            batchesLoaded++
            val m = min(batch, end - base)
            for (j in 0 until m) shared[j] = rb.tileSplats[base + j]
            for (tid in 0 until invocations) {
                if (done[tid]) continue
                val px = xs[tid].toFloat()
                val py = ys[tid].toFloat()
                for (j in 0 until m) {
                    val s = shared[j]
                    val dx = rb.means2d[2 * s] - px
                    val dy = rb.means2d[2 * s + 1] - py
                    val power = -0.5f * (rb.conics[3 * s] * dx * dx + rb.conics[3 * s + 2] * dy * dy) -
                        rb.conics[3 * s + 1] * dx * dy
                    if (power > 0f) continue
                    val a = min(Rasterizer.ALPHA_MAX, rb.opacities[s] * exp(power))
                    if (a < Rasterizer.ALPHA_MIN) continue
                    val next = tr[tid] * (1f - a)
                    if (next < Rasterizer.T_MIN) {
                        done[tid] = true
                        break
                    }
                    val w = a * tr[tid]
                    r[tid] += rb.colors[3 * s] * w
                    g[tid] += rb.colors[3 * s + 1] * w
                    b[tid] += rb.colors[3 * s + 2] * w
                    tr[tid] = next
                    last[tid] = base - begin + j + 1
                }
            }
            base += batch
        }
        for (tid in 0 until invocations) {
            if (!inside[tid]) continue
            val p = ys[tid] * rb.width + xs[tid]
            color[3 * p] = r[tid]
            color[3 * p + 1] = g[tid]
            color[3 * p + 2] = b[tid]
            alpha[p] = 1f - tr[tid]
            finalT[p] = tr[tid]
            contributors[p] = last[tid]
        }
    }
}
