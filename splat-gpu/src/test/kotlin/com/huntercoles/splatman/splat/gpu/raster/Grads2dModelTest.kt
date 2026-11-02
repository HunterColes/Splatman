package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.GpuSupport
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import com.huntercoles.splatman.splat.train.raster.SplatGrads
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The GPU backward kernels of stage 1 and 2, run as their pure-Kotlin model, against a linear scan and the CPU. */
class Grads2dModelTest {
    @Test
    fun `the gather finds every entry of the CPU's tile lists and equals a linear scan`() {
        for ((name, scene) in scenes()) {
            val (cloud, k) = scene
            val lists = Grads2dModel.Lists.of(cpu(cloud, k))
            assertTrue(lists.entries > 0, name)
            val rng = Random(42)
            val entryGrads = FloatArray(Grads2dLayout.ENTRY * lists.entries) { rng.nextFloat() * 2f - 1f }
            val got = Grads2dModel.gather(lists, entryGrads)
            assertEquals(lists.entries, Grads2dModel.found, name)
            assertArrayEquals(Grads2dModel.linearScan(lists, entryGrads), got, name)
        }
    }

    @Test
    fun `the gather breaks depth ties by index and leaves culled splats at zero`() {
        val rng = Random(42)
        val tilesX = 8
        val tilesY = 6
        val n = 300
        // Three depths only, so most lists hold runs of equal depth bits ordered by index.
        val depths = FloatArray(n) { floatArrayOf(1f, 2.5f, 4f)[rng.nextInt(3)] }
        val radii = IntArray(n) { if (it % 7 == 0) 0 else 1 + rng.nextInt(40) }
        val means = FloatArray(2 * n) {
            if (it % 2 == 0) rng.nextFloat() * (tilesX * 16 + 40) - 20 else rng.nextFloat() * (tilesY * 16 + 40) - 20
        }
        val lists = lists(tilesX, tilesY, radii, means, depths)
        assertTrue(lists.entries > n, "entries ${lists.entries}")
        val entryGrads = FloatArray(Grads2dLayout.ENTRY * lists.entries) { rng.nextFloat() * 2f - 1f }
        val got = Grads2dModel.gather(lists, entryGrads)
        assertEquals(lists.entries, Grads2dModel.found)
        assertArrayEquals(Grads2dModel.linearScan(lists, entryGrads), got)
        for (i in 0 until n step 7) {
            for (j in 0 until Grads2dLayout.ENTRY) assertEquals(0f, got[Grads2dLayout.ENTRY * i + j], "culled $i")
        }
    }

    @Test
    fun `replay then gather matches the CPU's splatGrads2d within 1e-3 per group`() {
        for ((name, scene) in scenes()) {
            val (cloud, k) = scene
            val fwd = cpu(cloud, k)
            val dLdColor = dLdColor(k, 42)
            val grads = SplatGrads()
            Rasterizer(1).use { it.backward(cloud, k, pose, fwd, dLdColor, grads) }
            val want = grads.screenGrads2d()
            val entryGrads = Grads2dModel.replay(fwd, dLdColor)
            val got = Grads2dModel.gather(Grads2dModel.Lists.of(fwd), entryGrads)
            for ((group, slots) in GROUPS) {
                val err = relativeL2(want, got, slots)
                assertTrue(err <= TOLERANCE, "$name $group: relative L2 error $err")
            }
        }
    }

    @Test
    fun `entries no pixel reached are zeroed and a zero colour gradient gives zeros`() {
        val (cloud, k) = scenes().first().second
        val fwd = cpu(cloud, k)
        val zero = Grads2dModel.replay(fwd, FloatArray(3 * k.width * k.height))
        assertTrue(zero.all { it == 0f })
        val got = Grads2dModel.replay(fwd, dLdColor(k, 7))
        assertTrue(got.any { it != 0f })
        assertTrue(got.all { it.isFinite() })
    }

    @Test
    fun `kernel limits, bindings and constants`() {
        val kernels = mapOf(
            "PACK" to BackwardTileShaders.PACK, "REPLAY" to BackwardTileShaders.REPLAY,
            "GATHER" to BackwardTileShaders.GATHER,
        )
        for ((name, src) in kernels) {
            val bindings = Regex("""binding = (\d+)""").findAll(src).map { it.groupValues[1].toInt() }.toList()
            assertEquals((0 until bindings.size).toList(), bindings.sorted(), name)
            assertTrue(bindings.size <= GpuSupport.MIN_SSBO_BINDINGS, name)
            assertTrue(!src.contains("atomic") || name == "REPLAY", "$name uses atomics")
        }
        // REPLAY's one atomic is the shared-memory max of the tile's contributor counts; nothing global.
        assertEquals(1, Regex("atomic").findAll(BackwardTileShaders.REPLAY).count())
        assertTrue(BackwardTileShaders.REPLAY.contains("atomicMax(maxCount"))
        for (f in floatArrayOf(Rasterizer.ALPHA_MAX, Rasterizer.ALPHA_MIN)) {
            val bits = Integer.toHexString(java.lang.Float.floatToRawIntBits(f))
            assertTrue(BackwardTileShaders.REPLAY.contains("uintBitsToFloat(0x${bits}u)"), "constant $f")
        }
        assertTrue(BackwardTileShaders.REPLAY.contains("local_size_x = 16, local_size_y = 16"))
        assertTrue(BackwardTileShaders.REPLAY_SHARED_BYTES <= MIN_SHARED_BYTES)
        assertEquals(BackwardTileShaders.PIXELS, BackwardTileShaders.LANES * BackwardTileShaders.PER_LANE)
        assertTrue(Grads2dLayout.ENTRY * BackwardTileShaders.LANES <= BackwardTileShaders.PIXELS)
        assertTrue(Grads2dLayout.SPLAT <= BackwardTileShaders.PIXELS)
        assertEquals(20 to 15, BackwardTileShaders.replayGroups(320, 240))
        assertEquals(5 to 3, BackwardTileShaders.replayGroups(70, 37))
        assertThrows<IllegalArgumentException> { BackwardTileShaders.replayGroups(0, 1) }
    }

    @Test
    fun `layout slots follow SplatGrads' entry order`() {
        assertEquals(9, Grads2dLayout.ENTRY)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 8), listOf(
            Grads2dLayout.U, Grads2dLayout.V, Grads2dLayout.CA, Grads2dLayout.CB, Grads2dLayout.CC,
            Grads2dLayout.R, Grads2dLayout.OP,
        ))
    }

    private fun relativeL2(want: DoubleArray, got: FloatArray, slots: IntRange): Double {
        var diff = 0.0
        var norm = 0.0
        val n = want.size / Grads2dLayout.ENTRY
        for (i in 0 until n) for (j in slots) {
            val w = want[Grads2dLayout.ENTRY * i + j]
            val d = got[Grads2dLayout.ENTRY * i + j] - w
            diff += d * d
            norm += w * w
        }
        assertTrue(norm > 0.0, "group $slots has no gradient")
        return sqrt(diff / norm)
    }

    /** Lists as the binning builds them: each tile's splats by (unsigned depth bits, index). */
    private fun lists(tilesX: Int, tilesY: Int, radii: IntArray, means: FloatArray, depths: FloatArray): Grads2dModel.Lists {
        val n = radii.size
        val probe = Grads2dModel.Lists(tilesX, tilesY, radii, means, depths, IntArray(tilesX * tilesY + 1), IntArray(0))
        val perTile = Array(tilesX * tilesY) { mutableListOf<Int>() }
        for (i in 0 until n) {
            if (radii[i] <= 0) continue
            val t = Grads2dModel.tileRect(probe, i)
            for (ty in t[2] until t[3]) for (tx in t[0] until t[1]) perTile[ty * tilesX + tx].add(i)
        }
        val order = Comparator<Int> { a, b ->
            val c = Integer.compareUnsigned(
                java.lang.Float.floatToRawIntBits(depths[a]), java.lang.Float.floatToRawIntBits(depths[b]),
            )
            if (c != 0) c else a.compareTo(b)
        }
        val start = IntArray(tilesX * tilesY + 1)
        val splats = mutableListOf<Int>()
        for (t in perTile.indices) {
            start[t] = splats.size
            splats.addAll(perTile[t].sortedWith(order))
        }
        start[perTile.size] = splats.size
        return Grads2dModel.Lists(tilesX, tilesY, radii, means, depths, start, splats.toIntArray())
    }

    /** Seeded dL/dColor in [-1, 1], with every 5th pixel zero (skipped by the replay). */
    private fun dLdColor(k: CameraIntrinsics, seed: Int): FloatArray {
        val rng = Random(seed)
        return FloatArray(3 * k.width * k.height) { if ((it / 3) % 5 == 0) 0f else rng.nextFloat() * 2f - 1f }
    }

    private fun scenes() = listOf(
        "50 @ 64x64" to (randomScene(50, small, 42) to small),
        "2000 @ 320x240" to (randomScene(2000, qvga, 42) to qvga),
        "300 @ 70x37, partial tiles" to (randomScene(300, odd, 42) to odd),
    )

    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics): RenderBuffers =
        RenderBuffers(k.width, k.height).also { out -> Rasterizer(1).use { it.forward(cloud, k, pose, out) } }

    private val small = CameraIntrinsics(fx = 56f, fy = 56f, cx = 31.5f, cy = 31.5f, width = 64, height = 64)
    private val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)
    private val odd = CameraIntrinsics(fx = 60f, fy = 60f, cx = 34.5f, cy = 18f, width = 70, height = 37)

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

    private companion object {
        /** ADR 0010: gradients per group, relative L2 error. */
        const val TOLERANCE = 1e-3

        /** GL_MAX_COMPUTE_SHARED_MEMORY_SIZE is at least this (GLES 3.1). */
        const val MIN_SHARED_BYTES = 16384

        val GROUPS = listOf(
            "mean2d" to Grads2dLayout.U..Grads2dLayout.V,
            "conic" to Grads2dLayout.CA..Grads2dLayout.CC,
            "colour" to Grads2dLayout.R..Grads2dLayout.R + 2,
            "opacity" to Grads2dLayout.OP..Grads2dLayout.OP,
        )
    }
}
