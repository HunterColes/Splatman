package com.huntercoles.splatman.splat.sort

/**
 * Back-to-front order of splats by view depth (ADR 0006): 16-bit keys between this call's finite min and max depth,
 * one stable counting pass. Depth is distance in front of the camera (larger = farther), so the farthest comes first.
 *
 * NaN depths come first (before every other depth), then +Inf, then the finite keys far to near, then -Inf; each
 * group and each key keeps index order (stable).
 *
 * Holds its scratch buffers: once they fit `count`, a call allocates nothing. Not thread-safe; give each worker its
 * own instance.
 */
class DepthSort(initialCapacity: Int = 0) {
    private val counts = IntArray(BUCKETS + 1)
    private var buckets = IntArray(initialCapacity)

    /** Writes the indices `0 until count` to `out[0 until count]`, farthest first. */
    fun backToFront(depths: FloatArray, count: Int, out: IntArray) {
        require(count in 0..depths.size && count <= out.size) {
            "count $count must fit depths (${depths.size}) and out (${out.size})"
        }
        if (count == 0) return
        if (buckets.size < count) buckets = IntArray(count)

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val d = depths[i]
            if (d.isFinite()) {
                if (d < min) min = d
                if (d > max) max = d
            }
        }
        val scale = if (max > min) MAX_KEY / (max.toDouble() - min.toDouble()) else 0.0

        counts.fill(0)
        for (i in 0 until count) {
            val d = depths[i]
            // Bucket 0 = NaN, 1 = +Inf, 2..65537 = finite keys far to near, 65538 = -Inf.
            val bucket = when {
                d.isNaN() -> 0
                d == Float.POSITIVE_INFINITY -> 1
                d == Float.NEGATIVE_INFINITY -> BUCKETS - 1
                else -> 2 + MAX_KEY - ((d.toDouble() - min) * scale).toInt().coerceIn(0, MAX_KEY)
            }
            buckets[i] = bucket
            counts[bucket + 1]++
        }
        for (b in 1..BUCKETS) counts[b] += counts[b - 1]
        for (i in 0 until count) {
            out[counts[buckets[i]]++] = i
        }
    }

    private companion object {
        const val MAX_KEY = 65535
        const val BUCKETS = MAX_KEY + 4 // NaN, +Inf, 65536 keys, -Inf
    }
}
