package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.model.TriangleMesh
import com.huntercoles.splatman.splat.sh.ShEval
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
 * Splat cloud to coloured triangle mesh (ADR 0018): density grid, triangle cap, Surface Nets, SH0 vertex colour.
 *
 * Progress: density 0 to 0.45 (every rebuild in the remaining share), surface 0.45 to 0.55, colour 0.55 to 0.95; the
 * writer owns 0.95 to 1, so [extract] ends at 0.95.
 */
object MeshExtractor {

    private const val DENSITY_END = 0.45f
    private const val SURFACE_END = 0.55f
    private const val COLOUR_END = 0.95f

    /** Share of the density progress left that one attempt takes; the crossing count fills the rest. */
    private const val ATTEMPT_SHARE = 0.9f

    /** A rebuild has floor(REBUILD_NUM / REBUILD_DEN x) points per axis. */
    private const val REBUILD_NUM = 4
    private const val REBUILD_DEN = 5

    /** Colour where no splat reaches a vertex. */
    private const val GREY = 0.5f

    /**
     * The mesh of [cloud]. Splats are selected as [DensityGrid.of] selects them; when twice the crossing count is
     * above [MeshSettings.maxTriangles], density is rebuilt at floor(0.8 x) points per axis, and the attempt at or under
     * [MeshSettings.minPointsPerAxis] is kept whatever its count. Vertex colour =
     * `sum w * clamp(0.5 + C0 * shDc, 0, 1) / sum w`, `w = alpha * G(vertex)`, over the splats (in index order) whose
     * clipped box overlaps the vertex's cell, on [MeshSettings.threads] z-slabs; mid grey where no splat reaches.
     *
     * [progress] gets a monotonic 0..0.95; [cancelled] is polled every [DensityGrid.CHUNK] splats and every z-slice and
     * throws [java.util.concurrent.CancellationException]. No surface throws [MeshEmptyException].
     */
    fun extract(
        cloud: SplatCloud,
        settings: MeshSettings = MeshSettings(),
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): TriangleMesh = extract(cloud, settings, progress, cancelled, null)

    internal fun extract(
        cloud: SplatCloud,
        settings: MeshSettings,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        stats: MeshStats?,
    ): TriangleMesh {
        progress(0f)
        val selection = select(cloud, settings, cancelled)
        if (selection.kept.isEmpty()) throw MeshEmptyException()
        stats?.splats = cloud.count
        stats?.candidates = selection.candidates
        stats?.kept = selection.kept.size
        val built = surface(cloud, settings, selection, progress, cancelled, stats)
        val surface = built.surface
        val frame = built.frame
        val map = IntArray(frame.points) { -1 }
        for (v in surface.cells.indices) map[surface.cells[v]] = v
        stats?.phase(
            "index",
            meshArrays(surface, cells = true),
            MeshStats.grid("cell-to-vertex", 4L * frame.points),
            MeshStats.splats("kept", 4L * selection.kept.size),
        )
        val colours = Colour(cloud, settings, frame, map, surface.positions).run(selection.kept, progress, cancelled)
        stats?.colourEvaluations = colours.evaluations
        stats?.phase(
            "colour",
            meshArrays(surface, cells = false),
            MeshStats.grid("cell-to-vertex", 4L * frame.points),
            MeshStats.splats("kept", 4L * selection.kept.size),
            MeshStats.splats("colour zLo, zHi", 8L * selection.kept.size),
            MeshStats.mesh("colour sums, weights", 16L * surface.vertexCount),
        )
        progress(COLOUR_END)
        return TriangleMesh(surface.positions, surface.normals, colours.rgb, surface.indices)
    }

    private fun meshArrays(s: SurfaceNets.Surface, cells: Boolean): List<MeshStats.Live> = listOfNotNull(
        MeshStats.mesh("positions", 4L * s.positions.size),
        MeshStats.mesh("normals", 4L * s.normals.size),
        MeshStats.mesh("indices", 4L * s.indices.size),
        if (cells) MeshStats.mesh("cells", 4L * s.cells.size) else null,
    )

    private class Built(val surface: SurfaceNets.Surface, val frame: Frame)

    /** Density attempts under the cap, then Surface Nets; the grid is unreachable once this returns. */
    @Suppress("LongParameterList")
    private fun surface(
        cloud: SplatCloud,
        settings: MeshSettings,
        selection: Selection,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        stats: MeshStats?,
    ): Built {
        var points = settings.maxPointsPerAxis
        var start = 0f
        var grid: DensityGrid? = null
        var crossings: Long
        while (true) {
            val a = start
            val b = start + (DENSITY_END - start) * ATTEMPT_SHARE
            grid = null
            val g = DensityGrid.of(cloud, settings, points, { p -> progress(a + (b - a) * p) }, cancelled)
            grid = g
            stats?.attempts?.add(points)
            stats?.phase(
                "density $points",
                emptyList(),
                MeshStats.grid("density", 4L * g.values.size),
                MeshStats.splats("DensityGrid candidates", 4L * selection.candidates),
                MeshStats.splats("DensityGrid kept, zLo, zHi", 12L * selection.kept.size),
                MeshStats.splats("kept", 4L * selection.kept.size),
            )
            crossings = SurfaceNets.countCrossings(g, settings.iso, cancelled)
            start = b
            if (2 * crossings <= settings.maxTriangles || points <= settings.minPointsPerAxis) break
            val next = max(points * REBUILD_NUM / REBUILD_DEN, 2 * settings.totalPad + 2)
            if (next >= points) break
            points = next
        }
        progress(DENSITY_END)
        val g = checkNotNull(grid)
        if (crossings == 0L) throw MeshEmptyException()
        val surface = SurfaceNets.extract(
            g,
            settings.iso,
            { p -> progress(DENSITY_END + (SURFACE_END - DENSITY_END) * p) },
            cancelled,
        )
        if (surface.triangleCount == 0) throw MeshEmptyException()
        stats?.gridPoints = g.values.size.toLong()
        stats?.densityBoxPoints = Geometry(cloud, settings, Frame.of(g)).boxPoints(selection.kept)
        stats?.phase(
            "surface",
            meshArrays(surface, cells = true),
            MeshStats.grid("density", 4L * g.values.size),
            MeshStats.grid("vertex slices", 8L * (g.nx - 1) * (g.ny - 1)),
            MeshStats.splats("kept", 4L * selection.kept.size),
        )
        return Built(surface, Frame.of(g))
    }

    private class Selection(val candidates: Int, val kept: IntArray)

    /** The splats [DensityGrid.of] sums: faint or non-finite ones skipped, then the quantile bounds. */
    private fun select(cloud: SplatCloud, settings: MeshSettings, cancelled: () -> Boolean): Selection {
        val candidates = IntArray(cloud.count)
        var m = 0
        for (i in 0 until cloud.count) {
            if (i % DensityGrid.CHUNK == 0 && cancelled()) throw CancellationException("mesh export cancelled")
            if (cloud.opacity(i) >= settings.minOpacity && finite(cloud, i)) candidates[m++] = i
        }
        if (m == 0) return Selection(0, IntArray(0))
        val p = cloud.positions
        val lo = DoubleArray(3)
        val hi = DoubleArray(3)
        for (axis in 0..2) {
            val coords = FloatArray(m) { p[3 * candidates[it] + axis] }
            coords.sort()
            val last = coords.size - 1
            lo[axis] = coords[floor(settings.boundsQuantile.toDouble() * last).toInt()].toDouble()
            hi[axis] = coords[ceil((1.0 - settings.boundsQuantile) * last).toInt().coerceAtMost(last)].toDouble()
        }
        var k = 0
        for (c in 0 until m) {
            val i = candidates[c]
            if ((0..2).all { a -> p[3 * i + a] >= lo[a] && p[3 * i + a] <= hi[a] }) candidates[k++] = i
        }
        return Selection(m, candidates.copyOf(k))
    }

    private fun finite(cloud: SplatCloud, i: Int): Boolean {
        for (a in 0..2) {
            if (!cloud.positions[3 * i + a].isFinite() || !cloud.logScales[3 * i + a].isFinite()) return false
        }
        for (a in 0..3) if (!cloud.rotations[4 * i + a].isFinite()) return false
        return true
    }

    /** What is kept of the grid once its values are dropped. */
    private class Frame(val nx: Int, val ny: Int, val nz: Int, val origin: Vec3, val cell: Float) {
        val points: Int get() = nx * ny * nz

        companion object {
            fun of(g: DensityGrid) = Frame(g.nx, g.ny, g.nz, g.origin, g.cell)
        }
    }

    private class Colours(val rgb: FloatArray, val evaluations: Long)

    /** The colour pass: every slab thread walks all splats in index order and touches only its own vertices. */
    private class Colour(
        private val cloud: SplatCloud,
        private val settings: MeshSettings,
        private val frame: Frame,
        private val map: IntArray,
        private val positions: FloatArray,
    ) {
        private val geo = Geometry(cloud, settings, frame)
        private val vertices = positions.size / 3
        private val sums = FloatArray(3 * vertices)
        private val weights = FloatArray(vertices)
        private val ox = frame.origin.x.toDouble()
        private val oy = frame.origin.y.toDouble()
        private val oz = frame.origin.z.toDouble()
        private val cellD = frame.cell.toDouble()

        fun run(kept: IntArray, progress: (Float) -> Unit, cancelled: () -> Boolean): Colours {
            val layers = frame.nz - 1
            val zLo = IntArray(kept.size)
            val zHi = IntArray(kept.size)
            val pr = DoubleArray(Geometry.SIZE)
            for (k in kept.indices) {
                geo.params(kept[k], pr)
                zLo[k] = max(0, geo.lo(pr, 2) - 1)
                zHi[k] = min(layers - 1, geo.hi(pr, 2))
            }
            val slabs = max(1, min(settings.threads, layers))
            val bounds = IntArray(slabs + 1) { (it.toLong() * layers / slabs).toInt() }
            val evaluations = LongArray(slabs)
            val pool = if (slabs > 1) {
                Executors.newFixedThreadPool(slabs) { r -> Thread(r).apply { isDaemon = true } }
            } else {
                null
            }
            try {
                var start = 0
                while (start < kept.size) {
                    if (cancelled()) throw CancellationException("mesh export cancelled")
                    val end = min(kept.size, start + DensityGrid.CHUNK)
                    val a = start
                    val tasks = (0 until slabs).map { s ->
                        Callable { evaluations[s] += slab(kept, a, end, zLo, zHi, bounds[s], bounds[s + 1]) }
                    }
                    if (pool == null) {
                        tasks.single().call()
                    } else {
                        for (f in pool.invokeAll(tasks)) {
                            try {
                                f.get()
                            } catch (e: ExecutionException) {
                                throw e.cause ?: e
                            }
                        }
                    }
                    start = end
                    progress(SURFACE_END + (COLOUR_END - SURFACE_END) * end / kept.size)
                }
            } finally {
                pool?.shutdownNow()
            }
            for (v in 0 until vertices) {
                val w = weights[v]
                for (c in 0..2) {
                    sums[3 * v + c] = if (w > 0f) (sums[3 * v + c] / w).coerceIn(0f, 1f) else GREY
                }
            }
            return Colours(sums, evaluations.sum())
        }

        /** Adds kept splats [from] until [to], in order, to the vertices of cell layers [za] until [zb]. */
        @Suppress("LongParameterList")
        private fun slab(kept: IntArray, from: Int, to: Int, zLo: IntArray, zHi: IntArray, za: Int, zb: Int): Long {
            val pr = DoubleArray(Geometry.SIZE)
            val nx = frame.nx
            val ny = frame.ny
            var evaluated = 0L
            for (k in from until to) {
                if (zHi[k] < za || zLo[k] >= zb) continue
                val i = kept[k]
                geo.params(i, pr)
                val x0 = max(0, geo.lo(pr, 0) - 1)
                val x1 = min(nx - 2, geo.hi(pr, 0))
                val y0 = max(0, geo.lo(pr, 1) - 1)
                val y1 = min(ny - 2, geo.hi(pr, 1))
                val r = channel(i, 0)
                val g = channel(i, 1)
                val b = channel(i, 2)
                for (z in max(zLo[k], za)..min(zHi[k], zb - 1)) {
                    for (y in y0..y1) {
                        val row = nx * (y + ny * z)
                        for (x in x0..x1) {
                            val v = map[row + x]
                            if (v < 0) continue
                            val w = weight(pr, v)
                            evaluated++
                            weights[v] += w
                            sums[3 * v] += w * r
                            sums[3 * v + 1] += w * g
                            sums[3 * v + 2] += w * b
                        }
                    }
                }
            }
            return evaluated
        }

        private fun channel(i: Int, c: Int): Float =
            (0.5 + ShEval.C0 * cloud.shDc[3 * i + c]).coerceIn(0.0, 1.0).toFloat()

        /** alpha * G(vertex [v]) of the splat whose parameters are [pr]. */
        private fun weight(pr: DoubleArray, v: Int): Float {
            val dx = (positions[3 * v] - ox) / cellD - pr[0]
            val dy = (positions[3 * v + 1] - oy) / cellD - pr[1]
            val dz = (positions[3 * v + 2] - oz) / cellD - pr[2]
            val m = pr[4] * dx * dx + pr[7] * dy * dy + pr[9] * dz * dz +
                2 * (pr[5] * dx * dy + pr[6] * dx * dz + pr[8] * dy * dz)
            return (pr[3] * exp(-0.5 * m)).toFloat()
        }
    }

    /**
     * Per-splat geometry in grid coordinates (cells), the same arithmetic as DensityGrid's (whose copy is private):
     * the boxes here are exactly the boxes the density pass summed.
     */
    private class Geometry(private val cloud: SplatCloud, private val settings: MeshSettings, frame: Frame) {
        private val ox = frame.origin.x.toDouble()
        private val oy = frame.origin.y.toDouble()
        private val oz = frame.origin.z.toDouble()
        private val cellD = frame.cell.toDouble()
        private val reach = settings.maxRadiusCells.toDouble()
        private val n = intArrayOf(frame.nx, frame.ny, frame.nz)

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

        /** Grid points the density pass evaluated for [kept]. */
        fun boxPoints(kept: IntArray): Long {
            val pr = DoubleArray(SIZE)
            var total = 0L
            for (i in kept) {
                params(i, pr)
                var box = 1L
                for (a in 0..2) box *= max(0, hi(pr, a) - lo(pr, a) + 1).toLong()
                total += box
            }
            return total
        }

        companion object {
            const val SIZE = 13

            /** DensityGrid's floor of a splat's standard deviation, cells. */
            private const val MIN_SIGMA_CELLS = 1e-4
        }
    }
}

/**
 * What one [MeshExtractor.extract] held, for the budget test (ADR 0018 point 8): the attempts' points per axis, grid
 * points and box evaluations, and per phase the arrays alive at once, each tagged with what it grows with.
 */
internal class MeshStats {
    enum class Scale { SPLATS, GRID, MESH }

    class Live(val name: String, val bytes: Long, val scale: Scale)

    var splats = 0
    var candidates = 0
    var kept = 0
    val attempts = mutableListOf<Int>()
    var gridPoints = 0L
    var densityBoxPoints = 0L
    var colourEvaluations = 0L
    val phases = LinkedHashMap<String, List<Live>>()

    fun phase(name: String, mesh: List<Live>, vararg live: Live) {
        phases[name] = mesh + live
    }

    companion object {
        fun splats(name: String, bytes: Long) = Live(name, bytes, Scale.SPLATS)
        fun grid(name: String, bytes: Long) = Live(name, bytes, Scale.GRID)
        fun mesh(name: String, bytes: Long) = Live(name, bytes, Scale.MESH)
    }
}
