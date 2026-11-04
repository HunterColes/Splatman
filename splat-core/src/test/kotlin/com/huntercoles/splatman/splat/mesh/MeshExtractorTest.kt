package com.huntercoles.splatman.splat.mesh

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.model.TriangleMesh
import com.huntercoles.splatman.splat.sh.ShEval
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class MeshExtractorTest {

    private class Splat(
        val pos: Vec3,
        val sigma: Vec3,
        val alpha: Float = 1f,
        val rgb: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f),
    )

    private fun logit(a: Float): Float = if (a >= 1f) 30f else ln(a / (1f - a))

    private fun cloud(splats: List<Splat>): SplatCloud {
        val n = splats.size
        val pos = FloatArray(3 * n)
        val scl = FloatArray(3 * n)
        val rot = FloatArray(4 * n)
        val op = FloatArray(n)
        val dc = FloatArray(3 * n)
        splats.forEachIndexed { i, s ->
            pos[3 * i] = s.pos.x; pos[3 * i + 1] = s.pos.y; pos[3 * i + 2] = s.pos.z
            scl[3 * i] = ln(s.sigma.x); scl[3 * i + 1] = ln(s.sigma.y); scl[3 * i + 2] = ln(s.sigma.z)
            rot[4 * i] = 1f
            op[i] = logit(s.alpha)
            for (c in 0..2) dc[3 * i + c] = ((s.rgb[c] - 0.5) / ShEval.C0).toFloat()
        }
        return SplatCloud(n, 0, pos, scl, rot, op, dc, FloatArray(0))
    }

    private val red = floatArrayOf(1f, 0f, 0f)
    private val blue = floatArrayOf(0f, 0f, 1f)
    private val h = 0.03f
    private val sigma = Vec3(h, h, h)

    /** Splats on a lattice of spacing [h], offset by h/2 so none sits on a centre plane, inside radius [r]. */
    private fun ball(c: Vec3, r: Float, colour: (Vec3) -> FloatArray = { floatArrayOf(0.5f, 0.5f, 0.5f) }): List<Splat> {
        val n = ceil(r / h).toInt()
        val out = mutableListOf<Splat>()
        for (k in -n until n) for (j in -n until n) for (i in -n until n) {
            val p = Vec3((i + 0.5f) * h, (j + 0.5f) * h, (k + 0.5f) * h)
            if (p.x * p.x + p.y * p.y + p.z * p.z <= r * r) {
                out += Splat(Vec3(c.x + p.x, c.y + p.y, c.z + p.z), sigma, rgb = colour(p))
            }
        }
        return out
    }

    private val settings = MeshSettings(maxPointsPerAxis = 64, minPointsPerAxis = 32, boundsQuantile = 0f, threads = 2)

    private val solid = cloud(ball(Vec3.ZERO, 0.5f))
    private val redBlue = cloud(ball(Vec3.ZERO, 0.5f) { p -> if (p.x > 0f) red else blue })

    /** Untruncated D along the unit direction [d] from the origin, bisected to the iso level between [a] and [b]. */
    private fun isoRadius(c: SplatCloud, d: Vec3, a0: Double, b0: Double): Double {
        fun density(t: Double): Double {
            var sum = 0.0
            for (i in 0 until c.count) {
                val dx = t * d.x - c.positions[3 * i]
                val dy = t * d.y - c.positions[3 * i + 1]
                val dz = t * d.z - c.positions[3 * i + 2]
                sum += c.opacity(i) * exp(-(dx * dx + dy * dy + dz * dz) / (2.0 * h * h))
            }
            return sum
        }
        var a = a0
        var b = b0
        repeat(40) {
            val m = (a + b) / 2
            if (density(m) >= settings.iso) a = m else b = m
        }
        return (a + b) / 2
    }

    private fun radius(m: TriangleMesh, v: Int): Double {
        val x = m.positions[3 * v]; val y = m.positions[3 * v + 1]; val z = m.positions[3 * v + 2]
        return sqrt((x * x + y * y + z * z).toDouble())
    }

    @Test
    fun `a solid ball gives a closed outward 2-manifold on the field's iso sphere`() {
        val m = MeshExtractor.extract(solid, settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(2, MeshChecks.euler(m.indices))
        assertTrue(MeshChecks.signedVolume(m.positions, m.indices) > 0.0)
        MeshChecks.assertUnitNormals(m.normals)
        val dirs = listOf(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 1f, 1f).normalized())
        val radii = dirs.map { isoRadius(solid, it, 0.4, 0.7) }
        val cell = DensityGrid.of(solid, settings).cell
        // The lattice ball is a sphere to well under a cell: one iso radius serves every direction.
        assertTrue(radii.max() - radii.min() < 0.5 * cell) { "iso surface not a sphere: $radii" }
        val iso = radii.average()
        var outward = 0
        for (v in 0 until m.vertexCount) {
            val r = radius(m, v)
            assertEquals(iso, r, 1.5 * cell) { "vertex $v at radius $r, iso $iso" }
            val dot = (0..2).sumOf { m.positions[3 * v + it].toDouble() * m.normals[3 * v + it] } / r
            if (dot > 0.9) outward++
        }
        assertTrue(outward >= 0.99 * m.vertexCount) { "$outward of ${m.vertexCount} normals outward" }
    }

    @Test
    fun `two separated balls give two closed components`() {
        val m = MeshExtractor.extract(cloud(ball(Vec3(-0.45f, 0f, 0f), 0.3f) + ball(Vec3(0.45f, 0f, 0f), 0.3f)), settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(2, MeshChecks.components(m.indices, m.vertexCount))
        assertEquals(4, MeshChecks.euler(m.indices))
    }

    @Test
    fun `a ball red on +x and blue on -x colours its vertices so away from the seam`() {
        val m = MeshExtractor.extract(redBlue, settings)
        var checked = 0
        for (v in 0 until m.vertexCount) {
            val x = m.positions[3 * v]
            if (abs(x) < 0.15f) continue
            val want = if (x > 0f) red else blue
            for (c in 0..2) assertEquals(want[c], m.colors[3 * v + c], 0.05f) { "vertex $v at x $x channel $c" }
            checked++
        }
        assertTrue(checked > m.vertexCount / 2) { "only $checked vertices away from the seam" }
    }

    @Test
    fun `a surface over maxTriangles is rebuilt coarser and stays under it`() {
        val full = MeshExtractor.extract(solid, settings)
        val at51 = MeshExtractor.extract(solid, settings.copy(maxPointsPerAxis = 51))
        assertTrue(at51.triangleCount < full.triangleCount)
        val cap = full.triangleCount - 1
        val stats = MeshStats()
        val m = MeshExtractor.extract(solid, settings.copy(maxTriangles = cap), {}, { false }, stats)
        assertEquals(listOf(64, 51), stats.attempts)
        assertTrue(m.triangleCount <= cap)
        assertArrayEquals(at51.indices, m.indices)
        assertArrayEquals(at51.positions, m.positions)
    }

    @Test
    fun `at minPointsPerAxis the attempt is kept whatever its count`() {
        val stats = MeshStats()
        val m = MeshExtractor.extract(solid, settings.copy(maxTriangles = 1), {}, { false }, stats)
        assertEquals(listOf(64, 51, 40, 32), stats.attempts)
        assertTrue(m.triangleCount > 1)
        val at32 = MeshExtractor.extract(solid, settings.copy(maxPointsPerAxis = 32))
        assertArrayEquals(at32.indices, m.indices)
        assertArrayEquals(at32.colors, m.colors)
    }

    @Test
    fun `output is bit-identical for 1 and 4 threads and across runs`() {
        val one = MeshExtractor.extract(redBlue, settings.copy(threads = 1))
        val four = MeshExtractor.extract(redBlue, settings.copy(threads = 4))
        val again = MeshExtractor.extract(redBlue, settings.copy(threads = 4))
        for (m in listOf(four, again)) {
            assertArrayEquals(one.positions, m.positions)
            assertArrayEquals(one.normals, m.normals)
            assertArrayEquals(one.colors, m.colors)
            assertArrayEquals(one.indices, m.indices)
        }
    }

    @Test
    fun `progress is monotonic from 0 and ends at 0_95`() {
        val seen = mutableListOf<Float>()
        MeshExtractor.extract(solid, settings.copy(maxTriangles = 1), { seen += it })
        assertEquals(0f, seen.first())
        assertEquals(0.95f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }) { "progress went back: $seen" }
        assertTrue(seen.any { it > 0.55f && it < 0.95f }) { "no colour progress" }
    }

    @Test
    fun `cancel throws CancellationException in every stage`() {
        var polls = 0
        MeshExtractor.extract(solid, settings, cancelled = { polls++; false })
        for (at in listOf(0, polls / 4, polls / 2, polls - 1)) {
            var n = 0
            assertThrows<CancellationException>("cancel at poll $at") {
                MeshExtractor.extract(solid, settings, cancelled = { n++ >= at })
            }
        }
    }

    @Test
    fun `an empty, an all-faint and a too-thin cloud throw MeshEmptyException`() {
        assertThrows<MeshEmptyException> { MeshExtractor.extract(cloud(emptyList()), settings) }
        assertThrows<MeshEmptyException> {
            MeshExtractor.extract(cloud(ball(Vec3.ZERO, 0.3f).map { Splat(it.pos, it.sigma, alpha = 0.01f) }), settings)
        }
        val sparse = (0 until 10).map { Splat(Vec3(it * 0.5f, 0f, 0f), Vec3(0.01f, 0.01f, 0.01f), alpha = 0.3f) }
        assertThrows<MeshEmptyException> { MeshExtractor.extract(cloud(sparse), settings) }
    }

    @Test
    fun `a single splat gives a closed blob at its iso radius`() {
        val c = Vec3(1f, 2f, 3f)
        val s = 0.05f
        val m = MeshExtractor.extract(cloud(listOf(Splat(c, Vec3(s, s, s), rgb = red))), settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(2, MeshChecks.euler(m.indices))
        val iso = s * sqrt(2 * ln(2.0)) // exp(-r^2 / 2 s^2) = 0.5
        val cell = settings.sigmas * s / settings.maxRadiusCells
        for (v in 0 until m.vertexCount) {
            val r = sqrt((0..2).sumOf { (m.positions[3 * v + it] - c[it]).toDouble().let { d -> d * d } })
            assertEquals(iso, r, 1.5 * cell)
            for (k in 0..2) assertEquals(red[k], m.colors[3 * v + k], 1e-5f)
        }
    }

    @Test
    fun `a flat wall of splats closes as a thin slab`() {
        val wall = mutableListOf<Splat>()
        for (j in -16 until 16) for (i in -16 until 16) {
            wall += Splat(Vec3((i + 0.5f) * h, (j + 0.5f) * h, 0f), Vec3(h, h, 0.005f))
        }
        val m = MeshExtractor.extract(cloud(wall), settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(2, MeshChecks.euler(m.indices))
        assertTrue(MeshChecks.signedVolume(m.positions, m.indices) > 0.0)
        MeshChecks.assertUnitNormals(m.normals)
    }

    @Test
    fun `a splat on the bounds edge gives its own closed component`() {
        val edge = Splat(Vec3(0.7f, 0f, 0f), sigma)
        val m = MeshExtractor.extract(cloud(ball(Vec3.ZERO, 0.3f) + edge), settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(2, MeshChecks.components(m.indices, m.vertexCount))
        assertTrue((0 until m.vertexCount).any { m.positions[3 * it] > 0.7f })
    }

    @Test
    fun `1e-6 m and 10 m splats give finite closed meshes`() {
        val c = Vec3(0.001f, 0f, 0f)
        val tiny = MeshExtractor.extract(cloud(listOf(Splat(c, Vec3(1e-6f, 1e-6f, 1e-6f)))), settings)
        MeshChecks.assertClosedManifold(tiny.indices)
        MeshChecks.assertUnitNormals(tiny.normals)
        for (v in 0 until tiny.vertexCount) {
            for (a in 0..2) assertEquals(c[a], tiny.positions[3 * v + a], 3e-6f)
        }
        val big = Vec3(10f, 10f, 10f)
        val huge = listOf(Splat(Vec3.ZERO, big), Splat(Vec3(1f, 0f, 0f), big), Splat(Vec3(0f, 1f, 0f), big))
        val m = MeshExtractor.extract(cloud(huge), settings)
        MeshChecks.assertClosedManifold(m.indices)
        assertEquals(3, MeshChecks.components(m.indices, m.vertexCount))
        MeshChecks.assertFinite(m.positions)
        MeshChecks.assertUnitNormals(m.normals)
        assertTrue(m.colors.all { it in 0f..1f })
    }

    @Test
    fun `a 200k-splat room at 256 points stays in the 192 MiB budget scaled to 1M splats`() {
        val rng = Random(448)
        // Room 6 x 3 x 4 m: floor, ceiling and four walls, splats thin along each face's normal, by area.
        val faces = listOf(24f, 24f, 12f, 12f, 18f, 18f)
        val total = faces.sum()
        val splats = List(200_000) {
            var pick = rng.nextFloat() * total
            var f = 0
            while (f < faces.size - 1 && pick >= faces[f]) pick -= faces[f++]
            val u = rng.nextFloat()
            val w = rng.nextFloat()
            val (pos, sig) = when (f) {
                0 -> Vec3(6 * u, 0f, 4 * w) to Vec3(0.04f, 0.01f, 0.04f)
                1 -> Vec3(6 * u, 3f, 4 * w) to Vec3(0.04f, 0.01f, 0.04f)
                2 -> Vec3(0f, 3 * u, 4 * w) to Vec3(0.01f, 0.04f, 0.04f)
                3 -> Vec3(6f, 3 * u, 4 * w) to Vec3(0.01f, 0.04f, 0.04f)
                4 -> Vec3(6 * u, 3 * w, 0f) to Vec3(0.04f, 0.04f, 0.01f)
                else -> Vec3(6 * u, 3 * w, 4f) to Vec3(0.04f, 0.04f, 0.01f)
            }
            Splat(pos, sig, 0.9f, floatArrayOf(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()))
        }
        val room = MeshSettings(threads = 4)
        val stats = MeshStats()
        val m = MeshExtractor.extract(cloud(splats), room, {}, { false }, stats)
        assertEquals(listOf(256), stats.attempts)
        val full = room.maxPointsPerAxis.toDouble().let { it * it * it }
        assertTrue(stats.gridPoints in 1..full.toLong())
        assertTrue(stats.densityBoxPoints > 0 && stats.colourEvaluations > 0)
        val scale = mapOf(
            MeshStats.Scale.SPLATS to 1_000_000.0 / stats.splats,
            MeshStats.Scale.GRID to full / stats.gridPoints,
            MeshStats.Scale.MESH to room.maxTriangles.toDouble() / m.triangleCount,
        )
        val mib = 1024.0 * 1024.0
        val peaks = stats.phases.mapValues { (_, live) -> live.sumOf { it.bytes * scale.getValue(it.scale) } / mib }
        println(
            "room: ${stats.splats} splats, ${stats.gridPoints} grid points, ${stats.densityBoxPoints} density box " +
                "points, ${stats.colourEvaluations} colour evaluations, ${m.vertexCount} vertices, " +
                "${m.triangleCount} triangles; scaled peak MiB per phase ${peaks.mapValues { "%.1f".format(it.value) }}",
        )
        assertTrue(peaks.values.max() <= 192.0) { "scaled peak over 192 MiB: $peaks" }
    }
}

private operator fun Vec3.get(axis: Int): Float = when (axis) {
    0 -> x
    1 -> y
    else -> z
}
