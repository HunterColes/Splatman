package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The opacity-weighted Gaussian density of a splat cloud sampled on a regular grid (ADR 0018):
 * `D(x) = sum_i alpha_i * exp(-1/2 (x - mu_i)^T Sigma_i^-1 (x - mu_i))`.
 *
 * Grid point (x, y, z) sits at `origin + (x, y, z) * cell` in frame W (metres); [values] is x-fastest,
 * index `x + nx * (y + ny * z)`. The array is not copied and is immutable by contract.
 */
class DensityGrid(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    /** Position of grid point (0, 0, 0), frame W, metres. */
    val origin: Vec3,
    /** Spacing between neighbouring grid points, metres, > 0. */
    val cell: Float,
    /** nx * ny * nz densities. */
    val values: FloatArray,
) {
    init {
        require(nx >= 1 && ny >= 1 && nz >= 1) { "grid must have >= 1 point per axis, got $nx x $ny x $nz" }
        require(values.size.toLong() == nx.toLong() * ny * nz) {
            "values must hold ${nx.toLong() * ny * nz} floats for $nx x $ny x $nz, got ${values.size}"
        }
        require(cell > 0f && cell.isFinite()) { "cell must be finite and > 0, got $cell" }
    }

    /** Density at grid point ([x], [y], [z]). */
    operator fun get(x: Int, y: Int, z: Int): Float = values[x + nx * (y + ny * z)]

    companion object {
        /** Splats between cancel checks and progress reports. */
        const val CHUNK = 4096

        /** Share of this stage's progress spent before the summation (filter, bounds). */
        private const val PREP_SHARE = 0.1f

        /** Floor of a splat's standard deviation, cells: keeps a needle-thin splat's inverse finite. */
        private const val MIN_SIGMA_CELLS = 1e-4

        /**
         * Samples the density of [cloud] on at most [pointsPerAxis] points per axis.
         *
         * Splats with opacity below [MeshSettings.minOpacity] or any non-finite parameter are skipped; bounds are the
         * per-axis [MeshSettings.boundsQuantile] quantiles of the remaining centres, and splats outside them are
         * dropped. The bounds are padded by [MeshSettings.totalPad] cells; cell = longest side /
         * (pointsPerAxis - 1 - 2 pad). Each kept splat adds to the points of its [MeshSettings.sigmas] box, clipped to
         * [MeshSettings.maxRadiusCells] cells each side and to the inner grid (the outer [MeshSettings.padCells]
         * layers stay 0). The sum runs on [MeshSettings.threads] z-slabs, each point summed in splat-index order, so
         * the grid is bit-identical for any thread count.
         *
         * [progress] gets a monotonic 0..1 of this stage, ending at 1; [cancelled] is polled every [CHUNK] splats and
         * throws [CancellationException] when true. An empty or all-faint cloud gives an all-zero grid.
         */
        fun of(
            cloud: SplatCloud,
            settings: MeshSettings,
            pointsPerAxis: Int = settings.maxPointsPerAxis,
            progress: (Float) -> Unit = {},
            cancelled: () -> Boolean = { false },
        ): DensityGrid {
            val pad = settings.totalPad
            require(pointsPerAxis >= 2 * pad + 2) { "pointsPerAxis must be >= ${2 * pad + 2}, got $pointsPerAxis" }
            progress(0f)
            val candidates = candidates(cloud, settings, cancelled)
            if (candidates.isEmpty()) {
                val n = 2 * pad + 1
                progress(1f)
                return DensityGrid(n, n, n, Vec3.ZERO, 1f, FloatArray(n * n * n))
            }
            val p = cloud.positions
            val lo = DoubleArray(3)
            val hi = DoubleArray(3)
            for (axis in 0..2) {
                val coords = FloatArray(candidates.size) { p[3 * candidates[it] + axis] }
                coords.sort()
                val last = coords.size - 1
                lo[axis] = coords[floor(settings.boundsQuantile.toDouble() * last).toInt()].toDouble()
                hi[axis] = coords[ceil((1.0 - settings.boundsQuantile) * last).toInt().coerceAtMost(last)].toDouble()
            }
            val kept = candidates.filter { i ->
                (0..2).all { a -> p[3 * i + a] >= lo[a] && p[3 * i + a] <= hi[a] }
            }.toIntArray()
            val inner = pointsPerAxis - 1 - 2 * pad
            val longest = (0..2).maxOf { hi[it] - lo[it] }
            val cell = cellSize(cloud, kept, settings, longest / inner)
            val cellD = cell.toDouble()
            val n = IntArray(3) { a -> min(inner, ceil((hi[a] - lo[a]) / cellD).toInt()) + 1 + 2 * pad }
            val origin = Vec3(
                (lo[0] - pad * cellD).toFloat(),
                (lo[1] - pad * cellD).toFloat(),
                (lo[2] - pad * cellD).toFloat(),
            )
            val geo = Geometry(cloud, settings, origin, cell, n[0], n[1], n[2])
            val zLo = IntArray(kept.size)
            val zHi = IntArray(kept.size)
            val params = DoubleArray(Geometry.SIZE)
            for (k in kept.indices) {
                geo.params(kept[k], params)
                zLo[k] = geo.lo(params, 2)
                zHi[k] = geo.hi(params, 2)
            }
            progress(PREP_SHARE)
            val values = FloatArray(n[0] * n[1] * n[2])
            sum(geo, kept, zLo, zHi, values, settings.threads, progress, cancelled)
            progress(1f)
            return DensityGrid(n[0], n[1], n[2], origin, cell, values)
        }

        /** Indices, in order, of the splats with opacity >= minOpacity and every parameter finite. */
        private fun candidates(cloud: SplatCloud, settings: MeshSettings, cancelled: () -> Boolean): IntArray {
            val out = IntArray(cloud.count)
            var m = 0
            for (i in 0 until cloud.count) {
                if (i % CHUNK == 0 && cancelled()) throw CancellationException("mesh export cancelled")
                if (cloud.opacity(i) >= settings.minOpacity && finite(cloud, i)) out[m++] = i
            }
            return out.copyOf(m)
        }

        private fun finite(cloud: SplatCloud, i: Int): Boolean {
            for (a in 0..2) {
                if (!cloud.positions[3 * i + a].isFinite() || !cloud.logScales[3 * i + a].isFinite()) return false
            }
            for (a in 0..3) if (!cloud.rotations[4 * i + a].isFinite()) return false
            return true
        }

        /**
         * [fromBounds] when it is a usable size; else (one splat, or every centre at one point) the cell at which the
         * largest kept splat's box just reaches maxRadiusCells; else 1 m.
         */
        private fun cellSize(cloud: SplatCloud, kept: IntArray, settings: MeshSettings, fromBounds: Double): Float {
            val f = fromBounds.toFloat()
            if (f > 0f && f.isFinite()) return f
            var largest = 0f
            for (i in kept) for (a in 0..2) largest = max(largest, exp(cloud.logScales[3 * i + a]))
            val g = settings.sigmas * largest / settings.maxRadiusCells
            return if (g > 0f && g.isFinite()) g else 1f
        }

        @Suppress("LongParameterList")
        private fun sum(
            geo: Geometry,
            kept: IntArray,
            zLo: IntArray,
            zHi: IntArray,
            values: FloatArray,
            threads: Int,
            progress: (Float) -> Unit,
            cancelled: () -> Boolean,
        ) {
            val slabs = min(threads, geo.nz)
            val bounds = IntArray(slabs + 1) { (it.toLong() * geo.nz / slabs).toInt() }
            val pool = if (slabs > 1) {
                Executors.newFixedThreadPool(slabs) { r -> Thread(r).apply { isDaemon = true } }
            } else {
                null
            }
            try {
                var start = 0
                while (start < kept.size) {
                    if (cancelled()) throw CancellationException("mesh export cancelled")
                    val end = min(kept.size, start + CHUNK)
                    val a = start
                    if (pool == null) {
                        geo.addSlab(kept, a, end, zLo, zHi, 0, geo.nz, values)
                    } else {
                        val tasks = (0 until slabs).map { s ->
                            Callable { geo.addSlab(kept, a, end, zLo, zHi, bounds[s], bounds[s + 1], values) }
                        }
                        for (f in pool.invokeAll(tasks)) {
                            try {
                                f.get()
                            } catch (e: ExecutionException) {
                                throw e.cause ?: e
                            }
                        }
                    }
                    start = end
                    progress(PREP_SHARE + (1f - PREP_SHARE) * end / kept.size)
                }
            } finally {
                pool?.shutdownNow()
            }
        }
    }

    /** Per-splat geometry in grid coordinates (cells), recomputed the same way wherever it is needed. */
    private class Geometry(
        private val cloud: SplatCloud,
        private val settings: MeshSettings,
        origin: Vec3,
        cell: Float,
        val nx: Int,
        val ny: Int,
        val nz: Int,
    ) {
        private val ox = origin.x.toDouble()
        private val oy = origin.y.toDouble()
        private val oz = origin.z.toDouble()
        private val cellD = cell.toDouble()
        private val reach = settings.maxRadiusCells.toDouble()
        private val n = intArrayOf(nx, ny, nz)

        /**
         * Writes splat [i]'s parameters to [out]: centre in cells (0..2), opacity (3), inverse covariance in
         * cells^-2 as xx, xy, xz, yy, yz, zz (4..9), clipped box half-size in cells per axis (10..12).
         */
        fun params(i: Int, out: DoubleArray) {
            val p = cloud.positions
            out[0] = (p[3 * i] - ox) / cellD
            out[1] = (p[3 * i + 1] - oy) / cellD
            out[2] = (p[3 * i + 2] - oz) / cellD
            out[3] = cloud.opacity(i).toDouble()
            val q = cloud.rotations
            var w = q[4 * i].toDouble()
            var x = q[4 * i + 1].toDouble()
            var y = q[4 * i + 2].toDouble()
            var z = q[4 * i + 3].toDouble()
            val norm = sqrt(w * w + x * x + y * y + z * z)
            if (norm > 0.0) {
                w /= norm; x /= norm; y /= norm; z /= norm
            } else {
                w = 1.0
            }
            val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - w * z); val r02 = 2 * (x * z + w * y)
            val r10 = 2 * (x * y + w * z); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - w * x)
            val r20 = 2 * (x * z - w * y); val r21 = 2 * (y * z + w * x); val r22 = 1 - 2 * (x * x + y * y)
            val s = cloud.logScales
            val s0 = max(MIN_SIGMA_CELLS, exp(s[3 * i].toDouble()) / cellD)
            val s1 = max(MIN_SIGMA_CELLS, exp(s[3 * i + 1].toDouble()) / cellD)
            val s2 = max(MIN_SIGMA_CELLS, exp(s[3 * i + 2].toDouble()) / cellD)
            // Sigma^-1 = R S^-2 R^T, Sigma_kk = sum_j R_kj^2 s_j^2.
            val i0 = 1 / (s0 * s0); val i1 = 1 / (s1 * s1); val i2 = 1 / (s2 * s2)
            out[4] = r00 * r00 * i0 + r01 * r01 * i1 + r02 * r02 * i2
            out[5] = r00 * r10 * i0 + r01 * r11 * i1 + r02 * r12 * i2
            out[6] = r00 * r20 * i0 + r01 * r21 * i1 + r02 * r22 * i2
            out[7] = r10 * r10 * i0 + r11 * r11 * i1 + r12 * r12 * i2
            out[8] = r10 * r20 * i0 + r11 * r21 * i1 + r12 * r22 * i2
            out[9] = r20 * r20 * i0 + r21 * r21 * i1 + r22 * r22 * i2
            val k = settings.sigmas.toDouble()
            out[10] = min(reach, k * sqrt(r00 * r00 * s0 * s0 + r01 * r01 * s1 * s1 + r02 * r02 * s2 * s2))
            out[11] = min(reach, k * sqrt(r10 * r10 * s0 * s0 + r11 * r11 * s1 * s1 + r12 * r12 * s2 * s2))
            out[12] = min(reach, k * sqrt(r20 * r20 * s0 * s0 + r21 * r21 * s1 * s1 + r22 * r22 * s2 * s2))
        }

        /** First grid index of the box along [axis], inside the unpadded layers. */
        fun lo(params: DoubleArray, axis: Int): Int =
            max(settings.padCells.toDouble(), ceil(params[axis] - params[10 + axis])).toInt()

        /** Last grid index of the box along [axis], inside the unpadded layers (below [lo] when it misses). */
        fun hi(params: DoubleArray, axis: Int): Int =
            min((n[axis] - 1 - settings.padCells).toDouble(), floor(params[axis] + params[10 + axis])).toInt()

        /** Adds kept splats [from] until [to], in order, to the points with z in [za] until [zb]. */
        @Suppress("LongParameterList")
        fun addSlab(
            kept: IntArray,
            from: Int,
            to: Int,
            zLo: IntArray,
            zHi: IntArray,
            za: Int,
            zb: Int,
            values: FloatArray,
        ) {
            val pr = DoubleArray(SIZE)
            for (k in from until to) {
                if (zHi[k] < za || zLo[k] >= zb) continue
                params(kept[k], pr)
                val x0 = lo(pr, 0); val x1 = hi(pr, 0)
                val y0 = lo(pr, 1); val y1 = hi(pr, 1)
                val alpha = pr[3]
                val axx = pr[4]; val axy = pr[5]; val axz = pr[6]; val ayy = pr[7]; val ayz = pr[8]; val azz = pr[9]
                for (z in max(zLo[k], za)..min(zHi[k], zb - 1)) {
                    val dz = z - pr[2]
                    for (y in y0..y1) {
                        val dy = y - pr[1]
                        val row = nx * (y + ny * z)
                        for (x in x0..x1) {
                            val dx = x - pr[0]
                            val m = axx * dx * dx + ayy * dy * dy + azz * dz * dz +
                                2 * (axy * dx * dy + axz * dx * dz + ayz * dy * dz)
                            values[row + x] += (alpha * exp(-0.5 * m)).toFloat()
                        }
                    }
                }
            }
        }

        companion object {
            const val SIZE = 13
        }
    }
}
