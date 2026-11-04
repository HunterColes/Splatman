package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class DensityGridTest {

    private class Splat(
        val pos: Vec3,
        val sigma: Vec3,
        val rot: Quat = Quat(1f, 0f, 0f, 0f),
        val alpha: Float = 1f,
    )

    private fun logit(a: Float): Float = if (a >= 1f) 30f else ln(a / (1f - a))

    private fun cloud(splats: List<Splat>): SplatCloud {
        val n = splats.size
        val pos = FloatArray(3 * n)
        val scl = FloatArray(3 * n)
        val rot = FloatArray(4 * n)
        val op = FloatArray(n)
        splats.forEachIndexed { i, s ->
            pos[3 * i] = s.pos.x; pos[3 * i + 1] = s.pos.y; pos[3 * i + 2] = s.pos.z
            scl[3 * i] = ln(s.sigma.x); scl[3 * i + 1] = ln(s.sigma.y); scl[3 * i + 2] = ln(s.sigma.z)
            rot[4 * i] = s.rot.w; rot[4 * i + 1] = s.rot.x; rot[4 * i + 2] = s.rot.y; rot[4 * i + 3] = s.rot.z
            op[i] = logit(s.alpha)
        }
        return SplatCloud(n, 0, pos, scl, rot, op, FloatArray(3 * n), FloatArray(0))
    }

    private val tiny = Vec3(1e-6f, 1e-6f, 1e-6f)

    // Two anchors fix the bounds to [-1.14, 1.14]^3; at 243 points per axis (pad 7, 228 inner cells) cell = 0.01 m.
    private val anchors = listOf(Splat(Vec3(-1.14f, -1.14f, -1.14f), tiny), Splat(Vec3(1.14f, 1.14f, 1.14f), tiny))
    private val points = 243
    private val cell = 0.01f

    private fun index(g: DensityGrid, p: Vec3): IntArray = intArrayOf(
        ((p.x - g.origin.x) / g.cell).roundToInt(),
        ((p.y - g.origin.y) / g.cell).roundToInt(),
        ((p.z - g.origin.z) / g.cell).roundToInt(),
    )

    private fun at(g: DensityGrid, i: Int, j: Int, k: Int): DoubleArray = doubleArrayOf(
        g.origin.x.toDouble() + i * g.cell.toDouble(),
        g.origin.y.toDouble() + j * g.cell.toDouble(),
        g.origin.z.toDouble() + k * g.cell.toDouble(),
    )

    @Test
    fun `an isotropic splat reads 1 at its centre and exp(-1 over 2) one sigma away`() {
        val c = Vec3(0f, 0f, 0f)
        val g = DensityGrid.of(cloud(anchors + Splat(c, Vec3(2 * cell, 2 * cell, 2 * cell))), MeshSettings(), points)
        assertEquals(cell, g.cell, 1e-7f)
        val (i, j, k) = index(g, c).toList()
        assertEquals(1f, g[i, j, k], 1e-5f)
        val e = exp(-0.5).toFloat()
        assertEquals(e, g[i + 2, j, k], 1e-5f)
        assertEquals(e, g[i, j - 2, k], 1e-5f)
        assertEquals(e, g[i, j, k + 2], 1e-5f)
    }

    @Test
    fun `a rotated anisotropic splat matches the Gaussian at five points`() {
        val c = Vec3(0.003f, -0.002f, 0.001f)
        val sigma = Vec3(1.5f * cell, 0.6f * cell, 1.0f * cell)
        val q = Quat.fromAxisAngle(Vec3(1f, 2f, 3f).normalized(), 0.7f)
        val alpha = 0.8f
        val g = DensityGrid.of(cloud(anchors + Splat(c, sigma, q, alpha)), MeshSettings(), points)
        val cov = Covariance.world(Vec3(ln(sigma.x), ln(sigma.y), ln(sigma.z)), q).map { it.toDouble() }
        val inv = invert(cov)
        val (i, j, k) = index(g, c).toList()
        val offsets = listOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0),
            intArrayOf(0, -1, -1))
        for (o in offsets) {
            val p = at(g, i + o[0], j + o[1], k + o[2])
            val d = doubleArrayOf(p[0] - c.x, p[1] - c.y, p[2] - c.z)
            var m = 0.0
            for (r in 0..2) for (s in 0..2) m += d[r] * inv[3 * r + s] * d[s]
            val expected = alpha * exp(-0.5 * m)
            assertTrue(expected > 1e-3, "offset ${o.toList()} must sample the splat, got $expected")
            assertEquals(expected.toFloat(), g[i + o[0], j + o[1], k + o[2]], 1e-5f, "offset ${o.toList()}")
        }
    }

    @Test
    fun `points outside the clipped box read 0`() {
        val c = Vec3(0f, 0f, 0f)
        // 3 sigma along x is 9 cells, clipped to 5; along y and z 3 sigma is 1.5 cells.
        val g = DensityGrid.of(cloud(anchors + Splat(c, Vec3(3 * cell, 0.5f * cell, 0.5f * cell))), MeshSettings(), points)
        val (i, j, k) = index(g, c).toList()
        // 5 cells is the box's edge, where float rounding of the centre decides; 4 is inside, 6 outside.
        assertEquals(exp(-0.5 * 16.0 / 9.0).toFloat(), g[i + 4, j, k], 1e-5f)
        assertEquals(exp(-0.5 * 16.0 / 9.0).toFloat(), g[i - 4, j, k], 1e-5f)
        assertEquals(0f, g[i + 6, j, k])
        assertEquals(0f, g[i - 6, j, k])
        assertEquals(exp(-2.0).toFloat(), g[i, j + 1, k], 1e-5f)
        assertEquals(0f, g[i, j + 2, k])
        assertEquals(0f, g[i, j, k - 2])
    }

    /** A 10^3 lattice in [0, 0.9]^3, each splat wide enough that its box is clipped to 5 cells. */
    private fun lattice(sigma: Float = 0.1f): List<Splat> =
        (0 until 1000).map { Splat(Vec3(0.1f * (it % 10), 0.1f * (it / 10 % 10), 0.1f * (it / 100)), Vec3(sigma, sigma, sigma)) }

    @Test
    fun `faint splats and splats outside the quantile bounds add nothing`() {
        val settings = MeshSettings(threads = 1)
        val base = DensityGrid.of(cloud(lattice()), settings, 64)
        val cellSize = base.cell
        // The outlier sits 3 cells above the top layer of centres: summed, it would reach grid points.
        val outlier = Splat(Vec3(0.45f, 0.45f, 0.9f + 3 * cellSize), Vec3(cellSize, cellSize, cellSize))
        val faint = Splat(Vec3(0.45f, 0.45f, 0.45f), Vec3(0.05f, 0.05f, 0.05f), alpha = 0.04f)
        val with = DensityGrid.of(cloud(lattice() + outlier + faint), settings, 64)
        assertEquals(base.cell, with.cell)
        assertEquals(base.origin, with.origin)
        assertArrayEquals(base.values, with.values)
    }

    @Test
    fun `at most pointsPerAxis per axis and the outer padCells layers read 0`() {
        val settings = MeshSettings(threads = 2)
        for (p in listOf(64, 100)) {
            val g = DensityGrid.of(cloud(lattice()), settings, p)
            assertTrue(g.nx <= p && g.ny <= p && g.nz <= p, "${g.nx} x ${g.ny} x ${g.nz} for $p")
            assertEquals(p, g.nx)
            val pad = settings.padCells
            var inner = 0f
            for (z in 0 until g.nz) for (y in 0 until g.ny) for (x in 0 until g.nx) {
                val edge = minOf(x, y, z, g.nx - 1 - x, g.ny - 1 - y, g.nz - 1 - z)
                if (edge < pad) assertEquals(0f, g[x, y, z], "($x, $y, $z)")
                if (edge == pad) inner = maxOf(inner, g[x, y, z])
            }
            assertTrue(inner > 0f, "the layer inside the padding must be reached")
        }
    }

    private fun random(n: Int): List<Splat> {
        var s = 12345L
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 40).toFloat() / (1 shl 24))
        }
        return List(n) {
            Splat(
                Vec3(next() * 2 - 1, next(), next() * 3),
                Vec3(0.005f + next() * 0.05f, 0.005f + next() * 0.05f, 0.005f + next() * 0.05f),
                Quat(next() - 0.5f, next() - 0.5f, next() - 0.5f, next() - 0.5f),
                0.05f + next() * 0.95f,
            )
        }
    }

    @Test
    fun `the grid is bit-identical for 1 and 4 threads and across runs`() {
        val c = cloud(random(9000))
        val one = DensityGrid.of(c, MeshSettings(threads = 1), 96)
        val four = DensityGrid.of(c, MeshSettings(threads = 4), 96)
        val again = DensityGrid.of(c, MeshSettings(threads = 4), 96)
        assertTrue(one.values.any { it > 0.5f })
        assertArrayEquals(one.values, four.values)
        assertArrayEquals(four.values, again.values)
    }

    @Test
    fun `progress is monotonic and ends at 1`() {
        val seen = mutableListOf<Float>()
        DensityGrid.of(cloud(random(10000)), MeshSettings(threads = 3), 64, progress = { seen += it })
        assertTrue(seen.size > 3, "progress per chunk: $seen")
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "monotonic: $seen")
        assertTrue(seen.all { it in 0f..1f })
        assertEquals(1f, seen.last())
    }

    @Test
    fun `cancel throws CancellationException`() {
        assertThrows<CancellationException> {
            DensityGrid.of(cloud(random(100)), MeshSettings(), 64, cancelled = { true })
        }
        var calls = 0
        assertThrows<CancellationException> {
            DensityGrid.of(cloud(random(10000)), MeshSettings(threads = 2), 64, cancelled = { ++calls > 4 })
        }
    }

    @Test
    fun `an empty and an all-faint cloud give an all-zero grid`() {
        val empty = DensityGrid.of(cloud(emptyList()), MeshSettings())
        assertTrue(empty.values.all { it == 0f })
        val faint = DensityGrid.of(cloud(random(50).map { Splat(it.pos, it.sigma, it.rot, 0.01f) }), MeshSettings())
        assertTrue(faint.values.all { it == 0f })
        var last = -1f
        DensityGrid.of(cloud(emptyList()), MeshSettings(), progress = { last = it })
        assertEquals(1f, last)
    }

    @Test
    fun `one splat gives a grid with its opacity at its centre`() {
        val c = Vec3(0.3f, -0.2f, 1f)
        val g = DensityGrid.of(cloud(listOf(Splat(c, Vec3(0.02f, 0.01f, 0.03f), alpha = 0.7f))), MeshSettings())
        val pad = MeshSettings().totalPad
        assertEquals(2 * pad + 1, g.nx)
        val (i, j, k) = index(g, c).toList()
        assertEquals(pad, i)
        assertEquals(0.7f, g[i, j, k], 1e-5f)
        assertTrue(g[i + 1, j, k] > 0f)
    }

    @Test
    fun `NaN positions are dropped, not summed`() {
        val good = random(200)
        val bad = Splat(Vec3(Float.NaN, 0.5f, 0.5f), Vec3(0.05f, 0.05f, 0.05f))
        val bad2 = Splat(Vec3(0.1f, 0.5f, Float.POSITIVE_INFINITY), Vec3(0.05f, 0.05f, 0.05f))
        val clean = DensityGrid.of(cloud(good), MeshSettings(threads = 1), 64)
        val dirty = DensityGrid.of(cloud(good.take(100) + bad + good.drop(100) + bad2), MeshSettings(threads = 1), 64)
        assertEquals(clean.origin, dirty.origin)
        assertArrayEquals(clean.values, dirty.values)
        assertTrue(dirty.values.none { it.isNaN() })
    }

    @Test
    fun `rejects a grid too small for its padding and mismatched arrays`() {
        assertThrows<IllegalArgumentException> { DensityGrid.of(cloud(anchors), MeshSettings(), 15) }
        assertThrows<IllegalArgumentException> { DensityGrid(2, 2, 2, Vec3.ZERO, 1f, FloatArray(7)) }
        assertThrows<IllegalArgumentException> { DensityGrid(2, 2, 2, Vec3.ZERO, 0f, FloatArray(8)) }
        assertEquals(5f, DensityGrid(2, 2, 2, Vec3.ZERO, 1f, FloatArray(8) { it.toFloat() })[1, 0, 1])
    }

    private fun invert(m: List<Double>): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]
        // Symmetric [[a, b, c], [b, d, e], [c, e, f]].
        val c00 = d * f - e * e; val c01 = c * e - b * f; val c02 = b * e - c * d
        val c11 = a * f - c * c; val c12 = b * c - a * e; val c22 = a * d - b * b
        val det = a * c00 + b * c01 + c * c02
        return doubleArrayOf(c00, c01, c02, c01, c11, c12, c02, c12, c22).map { it / det }.toDoubleArray()
    }
}
