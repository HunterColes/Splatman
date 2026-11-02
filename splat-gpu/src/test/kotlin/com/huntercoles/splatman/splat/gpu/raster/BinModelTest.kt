package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The GPU binning algorithm, run as its pure-Kotlin model, against plain Kotlin and against the CPU rasteriser. */
class BinModelTest {
    @Test
    fun `blocked scan is the exclusive running sum at every level boundary`() {
        val r = Random(42)
        for (n in intArrayOf(0, 1, 2, 255, 1023, 1024, 1025, 4096, 1024 * 1024 + 3)) {
            val data = IntArray(n) { r.nextInt(0, 50) }
            val want = IntArray(n)
            var run = 0
            for (i in 0 until n) {
                want[i] = run
                run += data[i]
            }
            BinModel.scan(data, n)
            assertArrayEquals(want, data, "n = $n")
        }
    }

    @Test
    fun `scan leaves values past n alone`() {
        val data = intArrayOf(1, 2, 3, 99)
        BinModel.scan(data, 3)
        assertArrayEquals(intArrayOf(0, 1, 3, 99), data)
    }

    @Test
    fun `radix sort of 100k seeded keys is sortedWith by tile, depth, then input order`() {
        val r = Random(42)
        val n = 100_000
        val tileCount = 300
        // Few distinct depths, so ties are common and stability shows.
        val depthPool = FloatArray(2_000) { 0.2f + r.nextFloat() * 20f }
        val tiles = IntArray(n) { r.nextInt(tileCount) }
        val depths = IntArray(n) { SortKeys.depthKey(depthPool[r.nextInt(depthPool.size)]) }
        val vals = IntArray(n) { it }
        val (sx, sy, sv) = BinModel.sort(tiles, depths, vals, tileCount)

        val want = (0 until n).sortedWith(
            compareBy<Int> { tiles[it] }.thenBy { Float.fromBits(depths[it]) }.thenBy { it },
        )
        assertArrayEquals(want.toIntArray(), sv)
        for (k in 0 until n) {
            assertEquals(tiles[sv[k]], sx[k])
            assertEquals(depths[sv[k]], sy[k])
        }
    }

    @Test
    fun `radix sort with one tile and with three tile bytes`() {
        val r = Random(7)
        for (tileCount in intArrayOf(1, 70_000)) {
            val n = 5_000
            val tiles = IntArray(n) { r.nextInt(tileCount) }
            val depths = IntArray(n) { SortKeys.depthKey(0.2f + r.nextFloat() * 1000f) }
            val (_, _, sv) = BinModel.sort(tiles, depths, IntArray(n) { it }, tileCount)
            val want = (0 until n).sortedWith(
                compareBy<Int> { tiles[it] }.thenBy { Float.fromBits(depths[it]) }.thenBy { it },
            )
            assertArrayEquals(want.toIntArray(), sv, "$tileCount tiles")
        }
    }

    @Test
    fun `binning model gives the CPU rasteriser's tile lists exactly`() {
        for ((n, k) in listOf(50 to small, 2000 to qvga)) {
            val cloud = randomScene(n, k, 42)
            val cpu = RenderBuffers(k.width, k.height)
            Rasterizer(1).use { it.forward(cloud, k, pose, cpu) }
            val (start, splats) = BinModel.bin(cpu.radii, cpu.means2d, cpu.depths, n, cpu.tilesX, cpu.tilesY)
            assertArrayEquals(cpu.tileStart, start, "tileStart, $n splats")
            val total = cpu.tileStart[cpu.tileCount]
            assertTrue(total > n / 2, "the scene should cover several tiles: $total entries")
            assertArrayEquals(cpu.tileSplats.copyOf(total), splats, "tileSplats, $n splats")
        }
    }

    @Test
    fun `binning model with nothing visible`() {
        val radii = IntArray(5)
        val (start, splats) = BinModel.bin(radii, FloatArray(10), FloatArray(5) { 1f }, 5, 4, 4)
        assertArrayEquals(IntArray(17), start)
        assertEquals(0, splats.size)
    }

    private val small = CameraIntrinsics(fx = 56f, fy = 56f, cx = 31.5f, cy = 31.5f, width = 64, height = 64)
    private val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)

    /** A small turn about +Y, so the world -> camera rotation is not the identity. */
    private val pose = CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.05f), Vec3(0.02f, -0.01f, 0.03f))

    /** As in RasterizerForwardTest: [n] splats in front of the camera, spread over the image, depths 2..6 m. */
    private fun randomScene(n: Int, k: CameraIntrinsics, seed: Int): SplatCloud {
        val rng = Random(seed)
        val positions = FloatArray(3 * n)
        val logScales = FloatArray(3 * n)
        val rotations = FloatArray(4 * n)
        for (i in 0 until n) {
            val z = 2f + 4f * rng.nextFloat()
            positions[3 * i] = (rng.nextFloat() - 0.5f) * k.width / k.fx * z
            positions[3 * i + 1] = (rng.nextFloat() - 0.5f) * k.height / k.fy * z
            positions[3 * i + 2] = -z
            for (a in 0 until 3) logScales[3 * i + a] = ln(0.005 + 0.025 * rng.nextDouble()).toFloat()
            val q = FloatArray(4) { rng.nextFloat() * 2f - 1f }
            val norm = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
            for (a in 0 until 4) rotations[4 * i + a] = q[a] / norm
        }
        return SplatCloud(
            count = n, shDegree = 0, positions = positions, logScales = logScales, rotations = rotations,
            opacityLogits = FloatArray(n) { rng.nextFloat() * 6f - 3f },
            shDc = FloatArray(3 * n) { rng.nextFloat() * 4f - 2f },
            shRest = FloatArray(0),
        )
    }
}
