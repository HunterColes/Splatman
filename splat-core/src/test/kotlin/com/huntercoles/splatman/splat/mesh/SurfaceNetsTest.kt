package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

class SurfaceNetsTest {

    /** n^3 grid of cell [cell] at [origin] holding f(x, y, z) of each point's position. */
    private fun grid(n: Int, cell: Float, origin: Vec3, f: (Double, Double, Double) -> Double): DensityGrid {
        val values = FloatArray(n * n * n)
        for (z in 0 until n) for (y in 0 until n) for (x in 0 until n) {
            values[x + n * (y + n * z)] =
                f(origin.x + x * cell.toDouble(), origin.y + y * cell.toDouble(), origin.z + z * cell.toDouble())
                    .toFloat()
        }
        return DensityGrid(n, n, n, origin, cell, values)
    }

    // A sphere of radius 0.3 m in a 0.02 m grid: D = 0.5 + (R - r), inside where r <= R.
    private val r = 0.3
    private val cell = 0.02f
    private val sphere = grid(41, cell, Vec3(-0.4f, -0.4f, -0.4f)) { x, y, z -> 0.5 + r - sqrt(x * x + y * y + z * z) }

    @Test
    fun `a sphere field gives a closed outward 2-manifold of Euler characteristic 2`() {
        val s = SurfaceNets.extract(sphere, 0.5f)
        MeshChecks.assertClosedManifold(s.indices)
        assertEquals(2, MeshChecks.euler(s.indices))
        assertEquals(1, MeshChecks.components(s.indices, s.vertexCount))
        val volume = MeshChecks.signedVolume(s.positions, s.indices)
        assertEquals(4.0 / 3 * Math.PI * r * r * r, volume, 0.03 * volume) // wound CCW from outside
        MeshChecks.assertUnitNormals(s.normals)
        for (v in 0 until s.vertexCount) {
            val x = s.positions[3 * v]; val y = s.positions[3 * v + 1]; val z = s.positions[3 * v + 2]
            val len = sqrt(x * x + y * y + z * z)
            assertEquals(r, len.toDouble(), 0.5 * cell) { "vertex $v off the sphere" }
            val dot = (x * s.normals[3 * v] + y * s.normals[3 * v + 1] + z * s.normals[3 * v + 2]) / len
            assertTrue(dot > 0.99f) { "normal $v not radial: $dot" }
        }
    }

    @Test
    fun `countCrossings is half the triangle count and every vertex keeps its cell`() {
        val s = SurfaceNets.extract(sphere, 0.5f)
        assertEquals(2 * SurfaceNets.countCrossings(sphere, 0.5f), s.triangleCount.toLong())
        assertEquals(s.vertexCount, s.cells.toSet().size)
        for (v in 0 until s.vertexCount) {
            val c = s.cells[v]
            val n = sphere.nx
            val cx = c % n; val cy = c / n % n; val cz = c / (n * n)
            for ((a, i) in listOf(cx, cy, cz).withIndex()) {
                val lo = -0.4f + i * cell
                assertTrue(s.positions[3 * v + a] in lo - 1e-5f..lo + cell + 1e-5f) { "vertex $v outside cell $c" }
            }
        }
    }

    @Test
    fun `each quad splits on its shorter diagonal`() {
        val s = SurfaceNets.extract(sphere, 0.5f)
        val p = s.positions
        fun d2(a: Int, b: Int): Float =
            (0..2).map { p[3 * a + it] - p[3 * b + it] }.let { (x, y, z) -> x * x + y * y + z * z }
        for (q in 0 until s.triangleCount / 2) {
            val t0 = s.indices.copyOfRange(6 * q, 6 * q + 3).toSet()
            val t1 = s.indices.copyOfRange(6 * q + 3, 6 * q + 6).toSet()
            val shared = t0 intersect t1
            assertEquals(2, shared.size) { "quad $q halves share no diagonal" }
            val other = (t0 union t1) - shared
            val (a, b) = shared.toList()
            val (c, d) = other.toList()
            assertTrue(d2(a, b) <= d2(c, d)) { "quad $q split on its longer diagonal" }
        }
    }

    @Test
    fun `one inside point gives a closed cube of 8 vertices and 12 triangles, ties split on corners 0-2`() {
        val g = grid(3, 1f, Vec3.ZERO) { x, y, z -> if (x == 1.0 && y == 1.0 && z == 1.0) 1.0 else 0.0 }
        val s = SurfaceNets.extract(g, 0.5f)
        assertEquals(8, s.vertexCount)
        assertEquals(12, s.triangleCount)
        MeshChecks.assertClosedManifold(s.indices)
        assertEquals(2, MeshChecks.euler(s.indices))
        assertTrue(MeshChecks.signedVolume(s.positions, s.indices) > 0.0)
        for (v in 0 until 8) for (a in 0..2) {
            // Mean of the three crossings at 0.5 along each edge: 1/3 of the way from the cell's outer corner.
            val c = s.positions[3 * v + a]
            assertTrue(abs(c - 5f / 6f) < 1e-6f || abs(c - 7f / 6f) < 1e-6f) { "vertex $v axis $a at $c" }
        }
        for (q in 0 until 6) {
            // Square quads: (q0, q1, q2), (q0, q2, q3).
            assertEquals(s.indices[6 * q], s.indices[6 * q + 3])
            assertEquals(s.indices[6 * q + 2], s.indices[6 * q + 4])
        }
    }

    @Test
    fun `an inside-out field winds the other way`() {
        // Inside everywhere but a hole: the surface faces into the hole, so its signed volume is negative.
        val g = grid(5, 1f, Vec3.ZERO) { x, y, z -> if (x == 2.0 && y == 2.0 && z == 2.0) 0.0 else 1.0 }
        val s = SurfaceNets.extract(g, 0.5f)
        MeshChecks.assertClosedManifold(s.indices)
        assertTrue(MeshChecks.signedVolume(s.positions, s.indices) < 0.0)
        for (v in 0 until s.vertexCount) {
            val dot = (0..2).sumOf { (s.positions[3 * v + it] - 2.0) * s.normals[3 * v + it] }
            assertTrue(dot < 0.0) { "normal $v points out of the hole" }
        }
    }

    @Test
    fun `a field with no crossing gives an empty surface`() {
        val s = SurfaceNets.extract(grid(4, 1f, Vec3.ZERO) { _, _, _ -> 0.2 }, 0.5f)
        assertEquals(0, s.vertexCount)
        assertEquals(0, s.triangleCount)
        assertEquals(0L, SurfaceNets.countCrossings(grid(4, 1f, Vec3.ZERO) { _, _, _ -> 0.9 }, 0.5f))
    }

    @Test
    fun `progress is monotonic and ends at 1, cancel throws`() {
        val seen = mutableListOf<Float>()
        SurfaceNets.extract(sphere, 0.5f, { seen += it })
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        var calls = 0
        assertThrows<CancellationException> { SurfaceNets.extract(sphere, 0.5f, cancelled = { ++calls > 60 }) }
        assertThrows<CancellationException> { SurfaceNets.countCrossings(sphere, 0.5f) { true } }
    }
}
