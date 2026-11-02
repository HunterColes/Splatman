package com.huntercoles.splatman.splat.train.init

import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.model.SplatCloud
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The 3DGS initialisation (ADR 0005 stage 1): one isotropic Gaussian per seed point, log-scale = ln(mean distance
 * to its 3 nearest neighbours) clamped to [[MIN_SCALE], [MAX_SCALE]], identity rotation, opacity [INIT_OPACITY],
 * `shDc = (rgb / 255 - 0.5) / C0`, SH degree 0. Neighbours come from a uniform grid of about one point per cell.
 */
object SeedInit {
    /** Degree-0 real SH constant, 1 / (2 sqrt(pi)). */
    const val C0 = 0.28209479177387814

    /** Smallest splat standard deviation, metres (what duplicate points get). */
    const val MIN_SCALE = 0.001

    /** Largest splat standard deviation, metres (what an isolated point gets). */
    const val MAX_SCALE = 0.1

    /** Initial opacity of every splat. */
    const val INIT_OPACITY = 0.1

    /** Nearest neighbours averaged for the scale. */
    const val NEIGHBOURS = 3

    /** Past this distance the mean of 3 neighbour distances is at least [MAX_SCALE], so the search stops there. */
    private const val SEARCH_RADIUS = NEIGHBOURS * MAX_SCALE

    /** Hard cap on the dense grid, so nx * ny * nz always fits an Int array. */
    private const val MAX_GRID_CELLS = (1 shl 28).toDouble()

    fun fromSeeds(seeds: SeedPoints): SplatCloud {
        val n = seeds.count
        val logScales = FloatArray(3 * n)
        val rotations = FloatArray(4 * n)
        val opacity = FloatArray(n)
        val shDc = FloatArray(3 * n)
        val logit = ln(INIT_OPACITY / (1 - INIT_OPACITY)).toFloat()
        val scales = meanNeighbourDistances(seeds.positions, n)
        for (i in 0 until n) {
            val s = ln(scales[i].toDouble().coerceIn(MIN_SCALE, MAX_SCALE)).toFloat()
            logScales[3 * i] = s
            logScales[3 * i + 1] = s
            logScales[3 * i + 2] = s
            rotations[4 * i] = 1f
            opacity[i] = logit
            for (c in 0 until 3) {
                val rgb = (seeds.colours[3 * i + c].toInt() and 0xFF) / 255.0
                shDc[3 * i + c] = ((rgb - 0.5) / C0).toFloat()
            }
        }
        return SplatCloud(
            count = n,
            shDegree = 0,
            positions = seeds.positions.copyOf(),
            logScales = logScales,
            rotations = rotations,
            opacityLogits = opacity,
            shDc = shDc,
            shRest = FloatArray(0),
        )
    }

    /**
     * Mean Euclidean distance from each point to its [NEIGHBOURS] nearest others, metres, before clamping; a value
     * of [MAX_SCALE] or more only means "at least that" (the search stops at [SEARCH_RADIUS]). A cloud with fewer
     * than [NEIGHBOURS] other points averages the ones it has; a lone point gets [MAX_SCALE].
     */
    internal fun meanNeighbourDistances(positions: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n)
        if (n == 0) return out
        val grid = Grid(positions, n)
        val best = DoubleArray(NEIGHBOURS)
        for (i in 0 until n) out[i] = grid.meanNearest(i, best)
        return out
    }

    /** Dense uniform grid over the bounding box, points counting-sorted by cell; at most ~4N cells. */
    private class Grid(private val p: FloatArray, private val n: Int) {
        private val minX: Double
        private val minY: Double
        private val minZ: Double
        private val cell: Double
        private val nx: Int
        private val ny: Int
        private val nz: Int
        private val cellStart: IntArray
        private val sorted = IntArray(n)
        private val cellOf = IntArray(n)

        init {
            var x0 = Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var z0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            var z1 = -Double.MAX_VALUE
            for (i in 0 until n) {
                val x = p[3 * i].toDouble()
                val y = p[3 * i + 1].toDouble()
                val z = p[3 * i + 2].toDouble()
                x0 = min(x0, x); y0 = min(y0, y); z0 = min(z0, z)
                x1 = max(x1, x); y1 = max(y1, y); z1 = max(z1, z)
            }
            minX = x0
            minY = y0
            minZ = z0
            // A non-finite extent (NaN or infinite coordinates) collapses to one cell on that axis.
            val ex = finiteOrZero(x1 - x0)
            val ey = finiteOrZero(y1 - y0)
            val ez = finiteOrZero(z1 - z0)
            val maxCells = min(max(64.0, 4.0 * n), MAX_GRID_CELLS)
            // One point per cell by volume, then grow the cell until the dense grid is small enough (flat or huge
            // scenes). Counts are Doubles so the product cannot overflow; the loop ends because each count tends to 1.
            var s = max(cbrt(ex * ey * ez / n), 1e-5)
            while (cells(ex, s) * cells(ey, s) * cells(ez, s) > maxCells) s *= 1.25
            cell = s
            nx = cells(ex, s).toInt()
            ny = cells(ey, s).toInt()
            nz = cells(ez, s).toInt()
            val total = nx * ny * nz
            cellStart = IntArray(total + 1)
            for (i in 0 until n) {
                val c = index(axis(p[3 * i] - minX, nx), axis(p[3 * i + 1] - minY, ny), axis(p[3 * i + 2] - minZ, nz))
                cellOf[i] = c
                cellStart[c + 1]++
            }
            for (c in 0 until total) cellStart[c + 1] += cellStart[c]
            val fill = cellStart.copyOf(total)
            for (i in 0 until n) sorted[fill[cellOf[i]]++] = i
        }

        private fun cells(extent: Double, s: Double): Double = floor(extent / s) + 1

        private fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

        private fun axis(offset: Double, dim: Int): Int = min(max(floor(offset / cell).toInt(), 0), dim - 1)

        private fun index(ix: Int, iy: Int, iz: Int): Int = (iz * ny + iy) * nx + ix

        /** Mean distance from point [i] to its nearest neighbours; [best] is scratch (squared distances, ascending). */
        fun meanNearest(i: Int, best: DoubleArray): Float {
            best.fill(Double.POSITIVE_INFINITY)
            var found = 0
            val c = cellOf[i]
            val cx = c % nx
            val cy = (c / nx) % ny
            val cz = c / (nx * ny)
            val maxRing = max(nx, max(ny, nz))
            var r = 0
            var exhausted = false
            while (true) {
                found += scanShell(i, cx, cy, cz, r, best)
                // Every point not yet seen lies farther than r * cell from point i.
                val reach = r * cell
                if (found >= NEIGHBOURS && best[NEIGHBOURS - 1] <= reach * reach) break
                if (reach >= SEARCH_RADIUS) break
                if (r >= maxRing) {
                    exhausted = true
                    break
                }
                r++
            }
            val have = min(found, NEIGHBOURS)
            // Too few within the search radius: the missing neighbours lie beyond it, and so does the mean.
            if (have == 0 || (have < NEIGHBOURS && !exhausted)) return MAX_SCALE.toFloat()
            var sum = 0.0
            for (m in 0 until have) sum += sqrt(best[m])
            return (sum / have).toFloat()
        }

        /** Offers every point in the cells at Chebyshev distance exactly [r] from (cx, cy, cz); returns how many. */
        private fun scanShell(i: Int, cx: Int, cy: Int, cz: Int, r: Int, best: DoubleArray): Int {
            var seen = 0
            val qx = p[3 * i]
            val qy = p[3 * i + 1]
            val qz = p[3 * i + 2]
            for (dz in -r..r) {
                val z = cz + dz
                if (z < 0 || z >= nz) continue
                for (dy in -r..r) {
                    val y = cy + dy
                    if (y < 0 || y >= ny) continue
                    val onFace = dz == -r || dz == r || dy == -r || dy == r
                    var dx = -r
                    while (dx <= r) {
                        val x = cx + dx
                        if (x in 0 until nx) {
                            val cellIdx = index(x, y, z)
                            for (k in cellStart[cellIdx] until cellStart[cellIdx + 1]) {
                                val j = sorted[k]
                                if (j == i) continue
                                val ddx = (p[3 * j] - qx).toDouble()
                                val ddy = (p[3 * j + 1] - qy).toDouble()
                                val ddz = (p[3 * j + 2] - qz).toDouble()
                                insert(best, ddx * ddx + ddy * ddy + ddz * ddz)
                                seen++
                            }
                        }
                        // Off the y and z faces only the two x faces belong to the shell.
                        dx = if (onFace || dx == r) dx + 1 else r
                    }
                }
            }
            return seen
        }

        private fun insert(best: DoubleArray, d2: Double) {
            if (d2 >= best[best.size - 1]) return
            var m = best.size - 1
            while (m > 0 && best[m - 1] > d2) {
                best[m] = best[m - 1]
                m--
            }
            best[m] = d2
        }
    }
}
