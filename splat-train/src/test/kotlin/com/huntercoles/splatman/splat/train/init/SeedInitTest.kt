package com.huntercoles.splatman.splat.train.init

import com.huntercoles.splatman.splat.model.SeedPoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class SeedInitTest {

    private fun seeds(positions: FloatArray, colours: ByteArray = ByteArray(positions.size)) =
        SeedPoints(positions.size / 3, positions, colours)

    private fun grid(side: Int, step: Float): FloatArray {
        val out = FloatArray(3 * side * side * side)
        var k = 0
        for (z in 0 until side) for (y in 0 until side) for (x in 0 until side) {
            out[k++] = x * step
            out[k++] = y * step
            out[k++] = z * step
        }
        return out
    }

    private fun random(n: Int, extent: Float, seed: Int): FloatArray {
        val rng = Random(seed)
        return FloatArray(3 * n) { rng.nextFloat() * extent }
    }

    @Test
    fun `a regular 1 cm grid gives a 1 cm scale`() {
        val cloud = SeedInit.fromSeeds(seeds(grid(side = 12, step = 0.01f)))
        for (i in 0 until cloud.count) {
            val s = cloud.scale(i)
            assertEquals(0.01f, s.x, 1e-4f, "splat $i")
            assertEquals(s.x, s.y, 0f)
            assertEquals(s.x, s.z, 0f)
        }
    }

    @Test
    fun `an isolated point clamps to 10 cm`() {
        val positions = grid(side = 4, step = 0.01f) + floatArrayOf(5f, 5f, 5f)
        val cloud = SeedInit.fromSeeds(seeds(positions))
        assertEquals(0.1f, cloud.scale(cloud.count - 1).x, 1e-6f)
    }

    @Test
    fun `a lone point clamps to 10 cm`() {
        val cloud = SeedInit.fromSeeds(seeds(floatArrayOf(1f, 2f, 3f)))
        assertEquals(0.1f, cloud.scale(0).x, 1e-6f)
    }

    @Test
    fun `duplicate points get the 1 mm minimum`() {
        val p = floatArrayOf(0.3f, 0.2f, 0.1f)
        val cloud = SeedInit.fromSeeds(seeds(p + p + p + p + floatArrayOf(2f, 2f, 2f)))
        for (i in 0 until 4) assertEquals(0.001f, cloud.scale(i).x, 1e-7f, "splat $i")
    }

    @Test
    fun `empty seed points give an empty cloud`() {
        val cloud = SeedInit.fromSeeds(SeedPoints(0, FloatArray(0), ByteArray(0)))
        assertEquals(0, cloud.count)
        assertEquals(0, cloud.shDegree)
    }

    @Test
    fun `colour round-trips through the degree-0 SH within 1 in 255`() {
        val n = 256
        val colours = ByteArray(3 * n) { (it / 3).toByte() }
        val cloud = SeedInit.fromSeeds(seeds(random(n, 1f, seed = 1), colours))
        for (i in 0 until n) for (c in 0 until 3) {
            val back = 0.5 + SeedInit.C0 * cloud.shDc[3 * i + c]
            assertEquals(i / 255.0, back, 1.0 / 255, "splat $i channel $c")
        }
    }

    @Test
    fun `other parameters follow the 3DGS initialisation`() {
        val positions = random(50, 0.2f, seed = 2)
        val cloud = SeedInit.fromSeeds(seeds(positions))
        assertEquals(0, cloud.shDegree)
        assertEquals(0, cloud.shRest.size)
        assertTrue(positions.contentEquals(cloud.positions))
        for (i in 0 until cloud.count) {
            assertEquals(0.1f, cloud.opacity(i), 1e-6f)
            assertEquals(1f, cloud.rotations[4 * i])
            assertEquals(0f, cloud.rotations[4 * i + 1])
            assertEquals(0f, cloud.rotations[4 * i + 2])
            assertEquals(0f, cloud.rotations[4 * i + 3])
        }
    }

    @Test
    fun `grid neighbours match a brute-force search`() {
        // Clustered and scattered points over 1 m, so some scales clamp and some do not.
        val n = 600
        val p = random(n, 1f, seed = 3)
        for (i in 0 until 300) p[i] = p[i] * 0.05f
        val got = SeedInit.meanNeighbourDistances(p, n)
        for (i in 0 until n) {
            val d = (0 until n).filter { it != i }.map { j ->
                val dx = (p[3 * j] - p[3 * i]).toDouble()
                val dy = (p[3 * j + 1] - p[3 * i + 1]).toDouble()
                val dz = (p[3 * j + 2] - p[3 * i + 2]).toDouble()
                sqrt(dx * dx + dy * dy + dz * dz)
            }.sorted()
            val expected = d.take(3).average().coerceIn(SeedInit.MIN_SCALE, SeedInit.MAX_SCALE)
            val actual = got[i].toDouble().coerceIn(SeedInit.MIN_SCALE, SeedInit.MAX_SCALE)
            assertEquals(expected, actual, 1e-6, "point $i")
        }
    }

    @Test
    fun `log-scale is the natural log of the clamped mean distance`() {
        val cloud = SeedInit.fromSeeds(seeds(grid(side = 3, step = 0.02f)))
        val s = cloud.logScales[0]
        assertEquals(ln(0.02f), s, 1e-4f)
        assertEquals(0.02f, exp(s), 1e-5f)
    }

    @Test
    fun `200k seeded points finish in under 2 s`() {
        val small = seeds(random(20_000, 1f, seed = 4))
        SeedInit.fromSeeds(small) // warm the JIT
        val big = seeds(random(200_000, 2f, seed = 5))
        val start = System.nanoTime()
        val cloud = SeedInit.fromSeeds(big)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(200_000, cloud.count)
        assertTrue(ms < 2000, "took $ms ms")
    }

    @Test
    fun `a huge coordinate does not overflow the grid`() {
        val cloud = SeedInit.fromSeeds(seeds(floatArrayOf(0f, 0f, 0f, 3e38f, 0f, 0f)))
        assertEquals(2, cloud.count)
        for (i in 0 until 2) assertEquals(0.1f, cloud.scale(i).x, 1e-6f, "splat $i")
    }

    @Test
    fun `a flat 100 km plane does not overflow the grid`() {
        val plane = floatArrayOf(0f, 0f, 0f, 1e5f, 0f, 0f, 0f, 1e5f, 0f)
        val cluster = FloatArray(27).also {
            for (k in 0 until 9) {
                it[3 * k] = 10f + (k % 3) * 0.01f
                it[3 * k + 1] = 10f + (k / 3) * 0.01f
            }
        }
        val cloud = SeedInit.fromSeeds(seeds(plane + cluster))
        for (i in 0 until 3) assertEquals(0.1f, cloud.scale(i).x, 1e-6f, "far splat $i")
        // The 3x3 cluster's centre has 4 neighbours at 1 cm: still found in one coarse cell.
        assertEquals(0.01f, cloud.scale(3 + 4).x, 1e-4f)
    }

    @Test
    fun `non-finite coordinates do not crash`() {
        val positions = floatArrayOf(0f, 0f, 0f, Float.NaN, 0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f, 0.01f, 0f, 0f)
        val cloud = SeedInit.fromSeeds(seeds(positions))
        assertEquals(4, cloud.count)
    }
}
