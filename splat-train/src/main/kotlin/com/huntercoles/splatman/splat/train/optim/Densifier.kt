package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Adaptive density control of ADR 0005 / 3DGS, one call per densifying iteration:
 * - prune splats with opacity under [OptimSettings.pruneOpacity] or largest scale over
 *   [OptimSettings.pruneScaleFraction] x extent (oversize);
 * - of the rest, those whose mean view-space gradient is at least [OptimSettings.densifyGradThreshold] are cloned
 *   (largest scale at most [OptimSettings.percentDense] x extent: an exact copy) or split (larger: replaced by two
 *   children drawn from the splat's own Gaussian, scales divided by [OptimSettings.splitScaleDivisor]);
 * - each clone or split adds one splat, so at most `maxSplats - kept` are taken, highest gradient first (ties by
 *   index). A cloud already at or over the cap does not grow and is never cut.
 *
 * The output is the kept splats in their old order, then the clones, then the split children, both in source order.
 */
internal object Densifier {

    class Result(
        val cloud: SplatCloud,
        /** Per new splat, the old index it keeps its optimiser state from, or -1 (clones and split children). */
        val source: IntArray,
        val cloned: Int,
        val split: Int,
        val pruned: Int,
    )

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun densify(
        cloud: SplatCloud,
        meanGrads: FloatArray,
        extent: Double,
        maxSplats: Int,
        settings: OptimSettings,
        random: Random,
    ): Result {
        val n = cloud.count
        val maxScale = DoubleArray(n) { i ->
            exp(max(cloud.logScales[3 * i], max(cloud.logScales[3 * i + 1], cloud.logScales[3 * i + 2])).toDouble())
        }
        val prune = BooleanArray(n) { i ->
            !(cloud.opacity(i) >= settings.pruneOpacity) || !(maxScale[i] <= settings.pruneScaleFraction * extent)
        }
        val kept = prune.count { !it }
        val candidates = (0 until n)
            .filter { !prune[it] && meanGrads[it] >= settings.densifyGradThreshold }
            .sortedWith(compareByDescending<Int> { meanGrads[it] }.thenBy { it })
        val budget = max(0, maxSplats - kept)
        val chosen = candidates.take(budget)
        val isSplit = BooleanArray(n)
        val isClone = BooleanArray(n)
        for (i in chosen) {
            if (maxScale[i] <= settings.percentDense * extent) isClone[i] = true else isSplit[i] = true
        }
        val clones = (0 until n).filter { isClone[it] }
        val splits = (0 until n).filter { isSplit[it] }
        val survivors = (0 until n).filter { !prune[it] && !isSplit[it] }

        val total = survivors.size + clones.size + 2 * splits.size
        val out = Builder(cloud, total)
        val source = IntArray(total) { -1 }
        for (i in survivors) {
            source[out.size] = i
            out.copy(i)
        }
        for (i in clones) out.copy(i)
        val shrink = ln(settings.splitScaleDivisor).toFloat()
        for (i in splits) {
            repeat(2) {
                val j = out.copy(i)
                out.offsetPosition(j, sampleOffset(cloud, i, random))
                for (a in 0 until 3) out.logScales[3 * j + a] -= shrink
            }
        }
        return Result(out.build(), source, clones.size, splits.size, n - kept)
    }

    /** A draw from splat [i]'s Gaussian about its centre: `R * (s * z)`, z standard normal, world W metres. */
    private fun sampleOffset(cloud: SplatCloud, i: Int, random: Random): DoubleArray {
        val local = DoubleArray(3) { a -> exp(cloud.logScales[3 * i + a].toDouble()) * random.nextGaussian() }
        var w = cloud.rotations[4 * i].toDouble()
        var x = cloud.rotations[4 * i + 1].toDouble()
        var y = cloud.rotations[4 * i + 2].toDouble()
        var z = cloud.rotations[4 * i + 3].toDouble()
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (!(norm > 0.0)) return local
        w /= norm; x /= norm; y /= norm; z /= norm
        val (lx, ly, lz) = Triple(local[0], local[1], local[2])
        return doubleArrayOf(
            (1 - 2 * (y * y + z * z)) * lx + 2 * (x * y - w * z) * ly + 2 * (x * z + w * y) * lz,
            2 * (x * y + w * z) * lx + (1 - 2 * (x * x + z * z)) * ly + 2 * (y * z - w * x) * lz,
            2 * (x * z - w * y) * lx + 2 * (y * z + w * x) * ly + (1 - 2 * (x * x + y * y)) * lz,
        )
    }

    /** Fills fresh arrays for [total] splats by copying splats of [from]. */
    private class Builder(private val from: SplatCloud, total: Int) {
        private val k = from.shRestPerChannel
        val positions = FloatArray(3 * total)
        val logScales = FloatArray(3 * total)
        val rotations = FloatArray(4 * total)
        val opacityLogits = FloatArray(total)
        val shDc = FloatArray(3 * total)
        val shRest = FloatArray(3 * k * total)
        var size = 0
            private set

        fun copy(i: Int): Int {
            val j = size++
            System.arraycopy(from.positions, 3 * i, positions, 3 * j, 3)
            System.arraycopy(from.logScales, 3 * i, logScales, 3 * j, 3)
            System.arraycopy(from.rotations, 4 * i, rotations, 4 * j, 4)
            opacityLogits[j] = from.opacityLogits[i]
            System.arraycopy(from.shDc, 3 * i, shDc, 3 * j, 3)
            System.arraycopy(from.shRest, 3 * k * i, shRest, 3 * k * j, 3 * k)
            return j
        }

        fun offsetPosition(j: Int, d: DoubleArray) {
            for (a in 0 until 3) positions[3 * j + a] = (positions[3 * j + a] + d[a]).toFloat()
        }

        fun build() = SplatCloud(size, from.shDegree, positions, logScales, rotations, opacityLogits, shDc, shRest)
    }
}
