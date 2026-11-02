package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/**
 * A pure-Kotlin model of [BackwardTileShaders], step for step in fp32: [replay] is REPLAY (lockstep back-to-front walk
 * of a tile, per-entry reduction over [BackwardTileShaders.LANES] lanes), [gather] is GATHER (rect walk in tile order,
 * binary search on (depth bits, index)), and [linearScan] the reference the gather must equal: every list entry
 * added to its splat in list order.
 */
internal object Grads2dModel {
    private const val E = Grads2dLayout.ENTRY

    /** The tile lists and per-splat inputs the gather reads. */
    class Lists(
        val tilesX: Int, val tilesY: Int, val radii: IntArray, val means2d: FloatArray, val depths: FloatArray,
        val tileStart: IntArray, val tileSplats: IntArray,
    ) {
        val n: Int get() = radii.size
        val entries: Int get() = tileStart[tilesX * tilesY]

        companion object {
            fun of(rb: RenderBuffers) = Lists(
                rb.tilesX, rb.tilesY, rb.radii.copyOf(rb.splatCount), rb.means2d.copyOf(2 * rb.splatCount),
                rb.depths.copyOf(rb.splatCount), rb.tileStart.copyOf(), rb.tileSplats.copyOf(rb.tileStart[rb.tileCount]),
            )
        }
    }

    /** GATHER's tileRect, in its float arithmetic: x0, x1, y0, y1, end exclusive. */
    fun tileRect(l: Lists, i: Int): IntArray {
        val r = l.radii[i].toFloat()
        val u = l.means2d[2 * i]
        val v = l.means2d[2 * i + 1]
        val t = Rasterizer.TILE.toFloat()
        return intArrayOf(
            floor((u - r) / t).coerceIn(0f, l.tilesX.toFloat()).toInt(),
            (floor((u + r) / t) + 1f).coerceIn(0f, l.tilesX.toFloat()).toInt(),
            floor((v - r) / t).coerceIn(0f, l.tilesY.toFloat()).toInt(),
            (floor((v + r) / t) + 1f).coerceIn(0f, l.tilesY.toFloat()).toInt(),
        )
    }

    /** Entries GATHER found, of the last [gather] call (for the tests: every entry exactly once). */
    var found = 0
        private set

    /** GATHER over [entryGrads]: [E] floats per splat. */
    fun gather(l: Lists, entryGrads: FloatArray): FloatArray {
        val out = FloatArray(E * l.n)
        found = 0
        for (i in 0 until l.n) {
            if (l.radii[i] <= 0) continue
            val t = tileRect(l, i)
            val key = java.lang.Float.floatToRawIntBits(l.depths[i])
            for (ty in t[2] until t[3]) for (tx in t[0] until t[1]) {
                val tile = ty * l.tilesX + tx
                var lo = l.tileStart[tile]
                val end = l.tileStart[tile + 1]
                var hi = end
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    val s = l.tileSplats[mid]
                    val k = java.lang.Float.floatToRawIntBits(l.depths[s])
                    val c = Integer.compareUnsigned(k, key)
                    if (c < 0 || (c == 0 && s < i)) lo = mid + 1 else hi = mid
                }
                if (lo < end && l.tileSplats[lo] == i) {
                    found++
                    for (j in 0 until E) out[E * i + j] += entryGrads[E * lo + j]
                }
            }
        }
        return out
    }

    /** Every entry added to its splat in list order (fp32). */
    fun linearScan(l: Lists, entryGrads: FloatArray): FloatArray {
        val out = FloatArray(E * l.n)
        for (e in 0 until l.entries) {
            val s = l.tileSplats[e]
            for (j in 0 until E) out[E * s + j] += entryGrads[E * e + j]
        }
        return out
    }

    /** REPLAY over every tile of [rb] (a forward pass) against [dLdColor]: [E] floats per entry. */
    fun replay(rb: RenderBuffers, dLdColor: FloatArray): FloatArray {
        val out = FloatArray(E * rb.tileStart[rb.tileCount])
        for (t in 0 until rb.tileCount) replayTile(rb, dLdColor, t, out)
        return out
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun replayTile(rb: RenderBuffers, dLdColor: FloatArray, tile: Int, out: FloatArray) {
        val px0 = (tile % rb.tilesX) * Rasterizer.TILE
        val py0 = (tile / rb.tilesX) * Rasterizer.TILE
        val begin = rb.tileStart[tile]
        val end = rb.tileStart[tile + 1]
        val pixels = BackwardTileShaders.PIXELS
        val count = IntArray(pixels)
        val tr = FloatArray(pixels) { 1f }
        val g = Array(pixels) { FloatArray(3) }
        val behind = Array(pixels) { FloatArray(3) }
        for (tid in 0 until pixels) {
            val x = px0 + tid % Rasterizer.TILE
            val y = py0 + tid / Rasterizer.TILE
            if (x >= rb.width || y >= rb.height) continue
            val p = y * rb.width + x
            for (c in 0 until 3) g[tid][c] = dLdColor[3 * p + c]
            if (g[tid][0] != 0f || g[tid][1] != 0f || g[tid][2] != 0f) {
                count[tid] = rb.contributors[p]
                tr[tid] = rb.finalT[p]
            }
        }
        val top = min(count.max(), end - begin)
        for (k in begin + top until end) for (j in 0 until E) out[E * k + j] = 0f
        val part = Array(E) { FloatArray(pixels) }
        for (li in top downTo 1) {
            val e = begin + li - 1
            val s = rb.tileSplats[e]
            val ca = rb.conics[3 * s]
            val cb = rb.conics[3 * s + 1]
            val cc = rb.conics[3 * s + 2]
            val op = rb.opacities[s]
            for (tid in 0 until pixels) {
                val v = FloatArray(E)
                if (li <= count[tid]) {
                    val dx = rb.means2d[2 * s] - (px0 + tid % Rasterizer.TILE)
                    val dy = rb.means2d[2 * s + 1] - (py0 + tid / Rasterizer.TILE)
                    val power = -0.5f * (ca * dx * dx + cc * dy * dy) - cb * dx * dy
                    if (power <= 0f) {
                        val gauss = exp(power)
                        val raw = op * gauss
                        val alpha = min(Rasterizer.ALPHA_MAX, raw)
                        if (alpha >= Rasterizer.ALPHA_MIN) {
                            val capped = raw > Rasterizer.ALPHA_MAX
                            val inv = 1f / (1f - alpha)
                            tr[tid] *= inv
                            val w = alpha * tr[tid]
                            val gp = g[tid]
                            val bh = behind[tid]
                            var dLdAlpha = 0f
                            for (c in 0 until 3) {
                                val col = rb.colors[3 * s + c]
                                dLdAlpha += gp[c] * (col * tr[tid] - bh[c] * inv)
                            }
                            for (c in 0 until 3) {
                                bh[c] += rb.colors[3 * s + c] * w
                                v[Grads2dLayout.R + c] = w * gp[c]
                            }
                            if (!capped) {
                                v[Grads2dLayout.OP] = gauss * dLdAlpha
                                val dLdPower = dLdAlpha * alpha
                                v[Grads2dLayout.CA] = -0.5f * dx * dx * dLdPower
                                v[Grads2dLayout.CB] = -dx * dy * dLdPower
                                v[Grads2dLayout.CC] = -0.5f * dy * dy * dLdPower
                                v[Grads2dLayout.U] = -(ca * dx + cb * dy) * dLdPower
                                v[Grads2dLayout.V] = -(cc * dy + cb * dx) * dLdPower
                            }
                        }
                    }
                }
                for (j in 0 until E) part[j][tid] = v[j]
            }
            for (j in 0 until E) {
                var sum = 0f
                for (lane in 0 until BackwardTileShaders.LANES) {
                    var s2 = 0f
                    for (k in 0 until BackwardTileShaders.PER_LANE) s2 += part[j][lane * BackwardTileShaders.PER_LANE + k]
                    sum += s2
                }
                out[E * e + j] = sum
            }
        }
    }
}
