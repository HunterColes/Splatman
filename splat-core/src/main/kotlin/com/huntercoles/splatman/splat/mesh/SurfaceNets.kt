package com.huntercoles.splatman.splat.mesh

import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * Naive Surface Nets (S. Gibson, "Constrained elastic surface nets", 1998; ADR 0018 point 3) on a [DensityGrid].
 *
 * Inside is `D >= iso`. Cell (x, y, z) has corners (x..x+1, y..y+1, z..z+1); its id is the grid index of its
 * lowest corner, `x + nx * (y + ny * z)`. Every cell whose corners straddle the iso level gets one vertex at the mean
 * of its edge crossings (linear interpolation). Every crossed grid edge whose four neighbouring cells lie in the grid
 * gets one quad joining their vertices, split into two triangles on its shorter diagonal (ties on corners 0-2) and
 * wound counter-clockwise seen from outside. Vertex normal = -grad D, the central-difference gradients of the cell's
 * corners interpolated trilinearly at the vertex, unit length.
 *
 * Only two z-layers of cell-to-vertex indices are held at a time; the output arrays are sized exactly by a counting
 * pass first.
 */
object SurfaceNets {

    /** The surface of one [extract]: frame W, metres; [cells] holds each vertex's cell id. */
    class Surface(
        /** 3 floats per vertex. */
        val positions: FloatArray,
        /** 3 floats per vertex, unit, pointing outside (down the density). */
        val normals: FloatArray,
        /** 3 ints per triangle, counter-clockwise seen from outside. */
        val indices: IntArray,
        /** One cell id per vertex, `x + nx * (y + ny * z)` of the cell's lowest corner. */
        val cells: IntArray,
    ) {
        val vertexCount: Int get() = cells.size
        val triangleCount: Int get() = indices.size / 3
    }

    /** Corner k of a cell is at offset (k and 1, k shr 1 and 1, k shr 2 and 1). */
    private val EDGES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)

    /**
     * Crossed grid edges whose four neighbouring cells lie in the grid: exactly the quads of [extract], so twice this
     * is its triangle count. [cancelled] is polled every z-slice.
     */
    fun countCrossings(grid: DensityGrid, iso: Float, cancelled: () -> Boolean = { false }): Long {
        val nx = grid.nx
        val ny = grid.ny
        val nz = grid.nz
        val v = grid.values
        var count = 0L
        for (z in 0 until nz) {
            if (cancelled()) throw CancellationException("mesh export cancelled")
            for (y in 0 until ny) {
                val row = nx * (y + ny * z)
                for (x in 0 until nx) {
                    val inside = v[row + x] >= iso
                    // x-edge: cells (x, y-1..y, z-1..z); y-edge: (x-1..x, y, z-1..z); z-edge: (x-1..x, y-1..y, z).
                    if (x < nx - 1 && y in 1..ny - 2 && z in 1..nz - 2 && inside != (v[row + x + 1] >= iso)) count++
                    if (y < ny - 1 && x in 1..nx - 2 && z in 1..nz - 2 && inside != (v[row + x + nx] >= iso)) count++
                    if (z < nz - 1 && x in 1..nx - 2 && y in 1..ny - 2 && inside != (v[row + x + nx * ny] >= iso)) {
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * The iso surface of [grid] at [iso]. [progress] gets a monotonic 0..1 of this stage ending at 1; [cancelled] is
     * polled every z-slice and throws [CancellationException] when true. A grid with no crossing gives an empty
     * [Surface].
     */
    fun extract(
        grid: DensityGrid,
        iso: Float,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Surface {
        progress(0f)
        val quads = countCrossings(grid, iso, cancelled)
        val vertices = countVertices(grid, iso, cancelled)
        require(6 * quads <= Int.MAX_VALUE && 3L * vertices <= Int.MAX_VALUE) { "surface too large: $quads quads" }
        val out = Builder(grid, iso, vertices, (6 * quads).toInt())
        val layers = grid.nz - 1
        for (z in 0 until layers) {
            if (cancelled()) throw CancellationException("mesh export cancelled")
            out.layer(z)
            progress((z + 1).toFloat() / layers)
        }
        progress(1f)
        check(out.vertexCount == vertices && out.indexCount == out.indices.size) { "count pass and build disagree" }
        return Surface(out.positions, out.normals, out.indices, out.cells)
    }

    private fun countVertices(grid: DensityGrid, iso: Float, cancelled: () -> Boolean): Int {
        var count = 0
        for (z in 0 until grid.nz - 1) {
            if (cancelled()) throw CancellationException("mesh export cancelled")
            for (y in 0 until grid.ny - 1) {
                for (x in 0 until grid.nx - 1) {
                    val m = mask(grid, iso, x, y, z)
                    if (m != 0 && m != FULL) count++
                }
            }
        }
        return count
    }

    private const val FULL = 0xFF

    /** Bit k set when corner k of cell (x, y, z) is inside. */
    private fun mask(grid: DensityGrid, iso: Float, x: Int, y: Int, z: Int): Int {
        val nx = grid.nx
        val plane = nx * grid.ny
        val base = x + nx * (y + grid.ny * z)
        val v = grid.values
        var m = 0
        if (v[base] >= iso) m = m or 1
        if (v[base + 1] >= iso) m = m or 2
        if (v[base + nx] >= iso) m = m or 4
        if (v[base + nx + 1] >= iso) m = m or 8
        if (v[base + plane] >= iso) m = m or 16
        if (v[base + plane + 1] >= iso) m = m or 32
        if (v[base + plane + nx] >= iso) m = m or 64
        if (v[base + plane + nx + 1] >= iso) m = m or 128
        return m
    }

    /** Fills the output one cell layer at a time, holding two layers of cell-to-vertex indices. */
    private class Builder(private val grid: DensityGrid, private val iso: Float, vertices: Int, indexCount: Int) {
        private val nx = grid.nx
        private val ny = grid.ny
        private val nz = grid.nz
        private val cx = nx - 1
        private val values = grid.values
        private val ox = grid.origin.x.toDouble()
        private val oy = grid.origin.y.toDouble()
        private val oz = grid.origin.z.toDouble()
        private val cell = grid.cell.toDouble()

        val positions = FloatArray(3 * vertices)
        val normals = FloatArray(3 * vertices)
        val cells = IntArray(vertices)
        val indices = IntArray(indexCount)
        var vertexCount = 0
            private set
        var indexCount = 0
            private set

        private var prev = IntArray(cx * (ny - 1)) { -1 }
        private var cur = IntArray(cx * (ny - 1)) { -1 }
        private val corner = DoubleArray(8)
        private val quad = IntArray(4)

        fun layer(z: Int) {
            val t = prev
            prev = cur
            cur = t
            for (y in 0 until ny - 1) {
                for (x in 0 until cx) cur[x + cx * y] = vertex(x, y, z)
            }
            // z-edges from grid plane z to z + 1: their four cells are all in this layer.
            for (y in 1..ny - 2) {
                for (x in 1..nx - 2) {
                    val a = values[x + nx * (y + ny * z)]
                    val b = values[x + nx * (y + ny * (z + 1))]
                    if ((a >= iso) == (b >= iso)) continue
                    quad[0] = cur[x - 1 + cx * (y - 1)]; quad[1] = cur[x + cx * (y - 1)]
                    quad[2] = cur[x + cx * y]; quad[3] = cur[x - 1 + cx * y]
                    emit(a >= iso)
                }
            }
            if (z < 1) return
            // x- and y-edges in grid plane z: their cells are in layers z - 1 (prev) and z (cur).
            for (y in 0 until ny) {
                for (x in 0 until nx) {
                    val a = values[x + nx * (y + ny * z)]
                    if (x < nx - 1 && y in 1..ny - 2 && (a >= iso) != (values[x + 1 + nx * (y + ny * z)] >= iso)) {
                        quad[0] = prev[x + cx * (y - 1)]; quad[1] = prev[x + cx * y]
                        quad[2] = cur[x + cx * y]; quad[3] = cur[x + cx * (y - 1)]
                        emit(a >= iso)
                    }
                    if (y < ny - 1 && x in 1..nx - 2 && (a >= iso) != (values[x + nx * (y + 1 + ny * z)] >= iso)) {
                        quad[0] = prev[x - 1 + cx * y]; quad[1] = cur[x - 1 + cx * y]
                        quad[2] = cur[x + cx * y]; quad[3] = prev[x + cx * y]
                        emit(a >= iso)
                    }
                }
            }
        }

        /**
         * Two triangles of the quad in [quad], cyclic counter-clockwise seen from the edge's +axis end; reversed when
         * that end is inside ([lowInside] false). Split on the shorter diagonal, ties on 0-2.
         */
        private fun emit(lowInside: Boolean) {
            val q0 = quad[0]
            val q2 = quad[2]
            val q1 = if (lowInside) quad[1] else quad[3]
            val q3 = if (lowInside) quad[3] else quad[1]
            check(q0 >= 0 && q1 >= 0 && q2 >= 0 && q3 >= 0) { "crossed edge without its four cell vertices" }
            if (dist2(q0, q2) <= dist2(q1, q3)) {
                tri(q0, q1, q2); tri(q0, q2, q3)
            } else {
                tri(q0, q1, q3); tri(q1, q2, q3)
            }
        }

        private fun tri(a: Int, b: Int, c: Int) {
            indices[indexCount++] = a
            indices[indexCount++] = b
            indices[indexCount++] = c
        }

        private fun dist2(a: Int, b: Int): Float {
            val dx = positions[3 * a] - positions[3 * b]
            val dy = positions[3 * a + 1] - positions[3 * b + 1]
            val dz = positions[3 * a + 2] - positions[3 * b + 2]
            return dx * dx + dy * dy + dz * dz
        }

        /** Adds the vertex of cell (x, y, z) and returns its index, or -1 when the cell does not straddle. */
        private fun vertex(x: Int, y: Int, z: Int): Int {
            val m = mask(grid, iso, x, y, z)
            if (m == 0 || m == FULL) return -1
            val base = x + nx * (y + ny * z)
            for (k in 0..7) corner[k] = values[base + (k and 1) + nx * ((k shr 1 and 1) + ny * (k shr 2 and 1))].toDouble()
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var n = 0
            for (e in 0 until EDGES.size / 2) {
                val i = EDGES[2 * e]
                val j = EDGES[2 * e + 1]
                if ((m shr i and 1) == (m shr j and 1)) continue
                val t = (iso - corner[i]) / (corner[j] - corner[i])
                sx += (i and 1) + t * ((j and 1) - (i and 1))
                sy += (i shr 1 and 1) + t * ((j shr 1 and 1) - (i shr 1 and 1))
                sz += (i shr 2 and 1) + t * ((j shr 2 and 1) - (i shr 2 and 1))
                n++
            }
            val fx = sx / n
            val fy = sy / n
            val fz = sz / n
            val v = vertexCount++
            positions[3 * v] = (ox + (x + fx) * cell).toFloat()
            positions[3 * v + 1] = (oy + (y + fy) * cell).toFloat()
            positions[3 * v + 2] = (oz + (z + fz) * cell).toFloat()
            normal(v, x, y, z, fx, fy, fz)
            cells[v] = base
            return v
        }

        /** -grad D at local (fx, fy, fz) of cell (x, y, z): trilinear over the corners' central differences. */
        @Suppress("LongParameterList")
        private fun normal(v: Int, x: Int, y: Int, z: Int, fx: Double, fy: Double, fz: Double) {
            var gx = 0.0
            var gy = 0.0
            var gz = 0.0
            for (k in 0..7) {
                val px = x + (k and 1)
                val py = y + (k shr 1 and 1)
                val pz = z + (k shr 2 and 1)
                val w = (if (k and 1 == 1) fx else 1 - fx) *
                    (if (k shr 1 and 1 == 1) fy else 1 - fy) *
                    (if (k shr 2 and 1 == 1) fz else 1 - fz)
                gx += w * diff(px, py, pz, 1, 0, 0, px, nx)
                gy += w * diff(px, py, pz, 0, 1, 0, py, ny)
                gz += w * diff(px, py, pz, 0, 0, 1, pz, nz)
            }
            val len = sqrt(gx * gx + gy * gy + gz * gz)
            if (len > 0.0 && len.isFinite()) {
                normals[3 * v] = (-gx / len).toFloat()
                normals[3 * v + 1] = (-gy / len).toFloat()
                normals[3 * v + 2] = (-gz / len).toFloat()
            } else {
                // A flat field: point from the inside corners' mean to the cell centre.
                fallback(v)
            }
        }

        private fun fallback(v: Int) {
            var dx = 0.0
            var dy = 0.0
            var dz = 0.0
            for (k in 0..7) {
                val s = if (corner[k] >= iso) -1.0 else 1.0
                dx += s * ((k and 1) - 0.5)
                dy += s * ((k shr 1 and 1) - 0.5)
                dz += s * ((k shr 2 and 1) - 0.5)
            }
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len > 0.0) {
                normals[3 * v] = (dx / len).toFloat()
                normals[3 * v + 1] = (dy / len).toFloat()
                normals[3 * v + 2] = (dz / len).toFloat()
            } else {
                normals[3 * v + 1] = 1f
            }
        }

        /** Central difference of D at grid point (x, y, z) along (ax, ay, az); one-sided at the faces. */
        @Suppress("LongParameterList")
        private fun diff(x: Int, y: Int, z: Int, ax: Int, ay: Int, az: Int, p: Int, n: Int): Double {
            val hi = if (p < n - 1) 1 else 0
            val lo = if (p > 0) 1 else 0
            if (hi + lo == 0) return 0.0
            val a = values[x + hi * ax + nx * (y + hi * ay + ny * (z + hi * az))]
            val b = values[x - lo * ax + nx * (y - lo * ay + ny * (z - lo * az))]
            return (a.toDouble() - b) / (hi + lo)
        }
    }
}
