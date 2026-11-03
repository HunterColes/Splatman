package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.train.raster.Rasterizer
import kotlin.math.floor

/**
 * A pure-Kotlin model of the GPU binning kernels, step for step: the blocked scan of [GpuScan], the per-block
 * histogram, digit-major scan and stable rank scatter of [GpuRadixSort], and COUNT / EMIT / TILE_START of
 * [GpuBinning]. Uint arithmetic is modelled with Int (wrapping adds, unsigned compares where they matter).
 */
internal object BinModel {
    /** [BinShaders.SCAN_BLOCKS] then [BinShaders.SCAN_ADD], recursively, in place over data[0 until n]. */
    fun scan(data: IntArray, n: Int) {
        if (n == 0) return
        val groups = (n + ScanPlan.BLOCK - 1) / ScanPlan.BLOCK
        val sums = IntArray(groups)
        for (g in 0 until groups) {
            val threadSums = IntArray(BinShaders.LOCAL)
            for (l in 0 until BinShaders.LOCAL) {
                for (k in 0 until ScanPlan.PER_THREAD) {
                    val idx = g * ScanPlan.BLOCK + l * ScanPlan.PER_THREAD + k
                    if (idx < n) threadSums[l] += data[idx]
                }
            }
            // Hillis-Steele, inclusive.
            val part = threadSums.copyOf()
            var off = 1
            while (off < BinShaders.LOCAL) {
                val prev = part.copyOf()
                for (l in off until BinShaders.LOCAL) part[l] = prev[l] + prev[l - off]
                off = off shl 1
            }
            for (l in 0 until BinShaders.LOCAL) {
                var run = part[l] - threadSums[l]
                for (k in 0 until ScanPlan.PER_THREAD) {
                    val idx = g * ScanPlan.BLOCK + l * ScanPlan.PER_THREAD + k
                    if (idx < n) {
                        val v = data[idx]
                        data[idx] = run
                        run += v
                    }
                }
            }
            sums[g] = part[BinShaders.LOCAL - 1]
        }
        if (groups == 1) return
        scan(sums, groups)
        for (i in 0 until n) data[i] += sums[i / ScanPlan.BLOCK]
    }

    /** [GpuRadixSort.sort]: (tile, depth) keys with values, sorted stably; returns the sorted triple arrays. */
    fun sort(tiles: IntArray, depths: IntArray, vals: IntArray, tileCount: Int): Triple<IntArray, IntArray, IntArray> {
        val n = tiles.size
        var kx = tiles.copyOf()
        var ky = depths.copyOf()
        var v = vals.copyOf()
        if (n == 0) return Triple(kx, ky, v)
        val blocks = SortKeys.blocks(n)
        for (p in 0 until SortKeys.passCount(tileCount)) {
            val word = SortKeys.word(p)
            val shift = SortKeys.shift(p)
            fun digit(i: Int) = ((if (word == SortKeys.TILE_WORD) kx[i] else ky[i]) ushr shift) and (SortKeys.BUCKETS - 1)
            val hist = IntArray(blocks * SortKeys.BUCKETS)
            for (i in 0 until n) hist[digit(i) * blocks + i / SortKeys.BLOCK]++
            scan(hist, hist.size)
            val ox = IntArray(n)
            val oy = IntArray(n)
            val ov = IntArray(n)
            for (b in 0 until blocks) {
                for (l in 0 until SortKeys.BLOCK) {
                    val idx = b * SortKeys.BLOCK + l
                    if (idx >= n) break
                    val d = digit(idx)
                    var rank = 0
                    for (j in 0 until l) if (digit(b * SortKeys.BLOCK + j) == d) rank++
                    val dst = hist[d * blocks + b] + rank
                    ox[dst] = kx[idx]
                    oy[dst] = ky[idx]
                    ov[dst] = v[idx]
                }
            }
            kx = ox
            ky = oy
            v = ov
        }
        return Triple(kx, ky, v)
    }

    /** The shader's tile rect, as in `Rasterizer.tileRect`. */
    fun tileRect(radius: Int, u: Float, v: Float, tilesX: Int, tilesY: Int): IntArray {
        val r = radius.toFloat()
        val t = Rasterizer.TILE.toFloat()
        fun clamp(x: Float, tiles: Int) = x.coerceIn(0f, tiles.toFloat()).toInt()
        return intArrayOf(
            clamp(floor((u - r) / t), tilesX),
            clamp(floor((u + r) / t) + 1f, tilesX),
            clamp(floor((v - r) / t), tilesY),
            clamp(floor((v + r) / t) + 1f, tilesY),
        )
    }

    /** COUNT, scan, EMIT, sort, TILE_START over a projection: (tileStart, tileSplats). */
    fun bin(
        radii: IntArray,
        means2d: FloatArray,
        depths: FloatArray,
        n: Int,
        tilesX: Int,
        tilesY: Int,
    ): Pair<IntArray, IntArray> {
        val tileCount = tilesX * tilesY
        val offsets = IntArray(n + 1)
        for (i in 0 until n) {
            if (radii[i] <= 0) continue
            val t = tileRect(radii[i], means2d[2 * i], means2d[2 * i + 1], tilesX, tilesY)
            offsets[i] = maxOf(t[1] - t[0], 0) * maxOf(t[3] - t[2], 0)
        }
        scan(offsets, n + 1)
        val total = offsets[n]
        val kx = IntArray(total)
        val ky = IntArray(total)
        val vals = IntArray(total)
        for (i in 0 until n) {
            if (radii[i] <= 0) continue
            val t = tileRect(radii[i], means2d[2 * i], means2d[2 * i + 1], tilesX, tilesY)
            var o = offsets[i]
            for (y in t[2] until t[3]) for (x in t[0] until t[1]) {
                kx[o] = y * tilesX + x
                ky[o] = SortKeys.depthKey(depths[i])
                vals[o] = i
                o++
            }
        }
        val (sx, _, sv) = sort(kx, ky, vals, tileCount)
        val start = IntArray(tileCount + 1) { t ->
            var lo = 0
            var hi = total
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sx[mid] < t) lo = mid + 1 else hi = mid
            }
            lo
        }
        return start to sv
    }
}
