package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.Dispatch
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type

/**
 * The binning sort keys and the radix schedule (ADR 0010): an entry's key is `uvec2(tile, depth bits)`, sorted by a
 * stable LSD radix sort, [RADIX_BITS] a pass: the [DEPTH_PASSES] depth passes first, then only as many tile passes as
 * the highest tile number needs. Over entries emitted in splat-index order this is the CPU's order: tile, then depth,
 * then index.
 */
object SortKeys {
    const val RADIX_BITS = 8
    const val BUCKETS = 1 shl RADIX_BITS

    /** Keys per histogram / scatter workgroup. */
    const val BLOCK = 256

    /** Depth keys are 32 bits. */
    const val DEPTH_PASSES = 32 / RADIX_BITS

    /** Key word of a pass: x holds the tile, y the depth bits. */
    const val TILE_WORD = 0
    const val DEPTH_WORD = 1

    /** Most entries one sort takes: its histogram and scatter run one workgroup per [BLOCK]. */
    const val MAX_ENTRIES = Dispatch.MAX_GROUPS * BLOCK

    /**
     * The depth key, the float's bits as an unsigned int: a visible splat's depth is > NEAR_Z > 0, and positive
     * floats order like their bits.
     */
    fun depthKey(depth: Float): Int {
        require(depth > 0f) { "depth keys need a positive depth, got $depth" }
        return depth.toRawBits()
    }

    /** Bits that tile numbers 0 until [tileCount] need: 0 for one tile. */
    fun tileBits(tileCount: Int): Int {
        require(tileCount >= 1) { "tileCount must be >= 1, was $tileCount" }
        return Int.SIZE_BITS - Integer.numberOfLeadingZeros(tileCount - 1)
    }

    fun tilePasses(tileCount: Int): Int = (tileBits(tileCount) + RADIX_BITS - 1) / RADIX_BITS

    /** Radix passes of a sort over [tileCount] tiles. */
    fun passCount(tileCount: Int): Int = DEPTH_PASSES + tilePasses(tileCount)

    /** Which key word pass [pass] reads. */
    fun word(pass: Int): Int = if (pass < DEPTH_PASSES) DEPTH_WORD else TILE_WORD

    /** The digit's right shift in pass [pass]. */
    fun shift(pass: Int): Int = RADIX_BITS * (if (pass < DEPTH_PASSES) pass else pass - DEPTH_PASSES)

    /** Histogram / scatter workgroups for [n] entries. */
    fun blocks(n: Int): Int = Dispatch.groups(n, BLOCK)
}

/** The blocked exclusive scan of [GpuScan]: blocks of [BLOCK] uints, then the block sums scanned the same way. */
object ScanPlan {
    const val PER_THREAD = 4
    const val BLOCK = BinShaders.LOCAL * PER_THREAD

    /** Lengths scanned at each level for [n] values: n, then its block count, until one block holds it all. */
    fun levels(n: Int): IntArray {
        require(n >= 0) { "n must be >= 0, was $n" }
        if (n == 0) return IntArray(0)
        val out = ArrayList<Int>()
        var len = n
        while (true) {
            out += len
            if (len <= BLOCK) break
            len = Dispatch.groups(len, BLOCK)
        }
        return out.toIntArray()
    }
}

/** Exclusive prefix sum of uints in place on the GPU. GL thread only; the level buffers only grow. */
class GpuScan(private val gl: GlesCompute) : AutoCloseable {
    private val blocks: ComputeProgram = gl.program(BinShaders.SCAN_BLOCKS)
    private val add: ComputeProgram = gl.program(BinShaders.SCAN_ADD)
    private val sums = ArrayList<Ssbo>()

    /** `data[i] = sum of data[0 until i]` for i < [n]; uint arithmetic (the sum must stay under 2^32). */
    fun scan(data: Ssbo, n: Int) {
        require(n >= 0 && n.toLong() * Std430.SCALAR_BYTES <= data.sizeBytes) { "$n uints in ${data.sizeBytes} bytes" }
        if (n > 0) level(data, n, 0)
    }

    private fun level(data: Ssbo, n: Int, depth: Int) {
        val groups = Dispatch.groups(n, ScanPlan.BLOCK)
        val s = sums(depth, groups)
        data.bind(0)
        s.bind(1)
        blocks.setUInt("n", n)
        blocks.dispatch(groups)
        if (groups == 1) return
        level(s, groups, depth + 1)
        data.bind(0)
        s.bind(1)
        add.setUInt("n", n)
        add.dispatchFor(n, BinShaders.LOCAL)
    }

    private fun sums(depth: Int, count: Int): Ssbo {
        val bytes = Std430.arrayBytes(Std430Type.UINT, count)
        if (depth == sums.size) sums += gl.ssbo(bytes)
        val s = sums[depth]
        if (s.sizeBytes < bytes) s.resize(Capacity.grow(s.sizeBytes, bytes))
        return s
    }

    override fun close() {
        sums.forEach { it.close() }
        sums.clear()
        blocks.close()
        add.close()
    }
}

/**
 * The stable LSD radix sort of `uvec2` keys with `uint` values on the GPU (ADR 0010): per pass a per-block digit
 * histogram, one [GpuScan] over it (digit-major, so the scan gives every (digit, block) its first slot), then a
 * stable scatter. GL thread only; the histogram buffer only grows.
 */
class GpuRadixSort(private val gl: GlesCompute, private val scan: GpuScan) : AutoCloseable {
    private val histogram: ComputeProgram = gl.program(BinShaders.HISTOGRAM)
    private val scatter: ComputeProgram = gl.program(BinShaders.SCATTER)
    private val hist: Ssbo = gl.ssbo(Std430.arrayBytes(Std430Type.UINT, SortKeys.BUCKETS))

    init {
        check(BinShaders.LOCAL == SortKeys.BLOCK && BinShaders.LOCAL == SortKeys.BUCKETS) {
            "the radix kernels need one invocation per key and per bucket"
        }
    }

    /**
     * Sorts the first [n] entries of `keys[from]` / `vals[from]` by [SortKeys.passCount] of [tileCount] passes,
     * ping-ponging with `keys[1 - from]` / `vals[1 - from]`. Returns the index holding the result.
     */
    fun sort(n: Int, tileCount: Int, keys: Array<Ssbo>, vals: Array<Ssbo>, from: Int): Int {
        require(keys.size == 2 && vals.size == 2 && from in 0..1) { "two key and two value buffers" }
        require(n in 0..SortKeys.MAX_ENTRIES) { "$n entries; one sort takes at most ${SortKeys.MAX_ENTRIES}" }
        val passes = SortKeys.passCount(tileCount)
        if (n == 0) return if (passes % 2 == 0) from else 1 - from
        val blocks = SortKeys.blocks(n)
        val histLen = blocks * SortKeys.BUCKETS
        val bytes = Std430.arrayBytes(Std430Type.UINT, histLen)
        if (hist.sizeBytes < bytes) hist.resize(Capacity.grow(hist.sizeBytes, bytes))
        var src = from
        for (p in 0 until passes) {
            val word = SortKeys.word(p)
            val shift = SortKeys.shift(p)
            keys[src].bind(0)
            hist.bind(1)
            histogram.setUInt("n", n)
            histogram.setUInt("blocks", blocks)
            histogram.setUInt("word", word)
            histogram.setUInt("shift", shift)
            histogram.dispatch(blocks)
            scan.scan(hist, histLen)
            keys[src].bind(0)
            vals[src].bind(1)
            keys[1 - src].bind(2)
            vals[1 - src].bind(3)
            hist.bind(4)
            scatter.setUInt("n", n)
            scatter.setUInt("blocks", blocks)
            scatter.setUInt("word", word)
            scatter.setUInt("shift", shift)
            scatter.dispatch(blocks)
            src = 1 - src
        }
        return src
    }

    override fun close() {
        hist.close()
        histogram.close()
        scatter.close()
    }
}
