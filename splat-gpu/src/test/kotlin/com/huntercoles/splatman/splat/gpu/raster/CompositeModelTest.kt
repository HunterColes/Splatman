package com.huntercoles.splatman.splat.gpu.raster

import com.huntercoles.splatman.splat.gpu.GpuSupport
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
import org.junit.jupiter.api.assertThrows
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The GPU compositing algorithm, run as its pure-Kotlin model, against `Rasterizer.compositeTile`; its layout. */
class CompositeModelTest {
    @Test
    fun `batched tile compositing gives the CPU rasteriser's image exactly`() {
        val scenes = listOf(
            "50 @ 64x64" to (randomScene(50, small, 42) to small),
            "2000 @ 320x240" to (randomScene(2000, qvga, 42) to qvga),
            "300 @ 70x37, partial tiles" to (randomScene(300, odd, 42) to odd),
        )
        for ((name, scene) in scenes) {
            val (cloud, k) = scene
            val cpu = cpu(cloud, k, pose)
            assertTrue(cpu.tileStart[cpu.tileCount] > 0, name)
            for (batch in intArrayOf(CompositeShaders.BATCH, 7, 1)) {
                assertSame(cpu, CompositeModel(cpu, batch), "$name, batch $batch")
            }
        }
    }

    @Test
    fun `a tile whose pixels have all stopped loads no further batch`() {
        // 600 wide, near-opaque splats over a 32x32 image: every pixel stops within a few entries of its tile list.
        val n = 600
        val k = CameraIntrinsics(fx = 56f, fy = 56f, cx = 15.5f, cy = 15.5f, width = 32, height = 32)
        val cloud = SplatCloud(
            count = n, shDegree = 0,
            positions = FloatArray(3 * n) { if (it % 3 == 2) -(2f + it / 3 / n.toFloat()) else 0f },
            logScales = FloatArray(3 * n) { 0f },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n) { ln(0.99 / 0.01).toFloat() },
            shDc = FloatArray(3 * n) { (it % 7) * 0.1f },
            shRest = FloatArray(0),
        )
        val identity = CameraPose(Quat(1f, 0f, 0f, 0f), Vec3(0f, 0f, 0f))
        val cpu = cpu(cloud, k, identity)
        for (t in 0 until cpu.tileCount) assertEquals(n, cpu.tileStart[t + 1] - cpu.tileStart[t], "tile $t")
        assertTrue(cpu.contributors.all { it in 1 until CompositeShaders.BATCH })
        val model = CompositeModel(cpu)
        assertSame(cpu, model, "opaque stack")
        assertEquals(cpu.tileCount, model.batchesLoaded)
    }

    @Test
    fun `one workgroup per tile`() {
        assertEquals(4 to 4, CompositeShaders.groups(64, 64))
        assertEquals(20 to 15, CompositeShaders.groups(320, 240))
        assertEquals(5 to 3, CompositeShaders.groups(70, 37))
        assertEquals(1 to 1, CompositeShaders.groups(1, 1))
        assertEquals(256, CompositeShaders.BATCH)
        assertThrows<IllegalArgumentException> { CompositeShaders.groups(0, 16) }
    }

    @Test
    fun `the kernel uses at most the probed SSBO bindings and the CPU's exact constants`() {
        val src = CompositeShaders.COMPOSITE
        val bindings = Regex("""binding = (\d+)""").findAll(src).map { it.groupValues[1].toInt() }.toList()
        assertEquals((0 until bindings.size).toList(), bindings.sorted())
        assertTrue(bindings.size <= GpuSupport.MIN_SSBO_BINDINGS)
        for (f in floatArrayOf(Rasterizer.ALPHA_MAX, Rasterizer.ALPHA_MIN, Rasterizer.T_MIN)) {
            val bits = Integer.toHexString(java.lang.Float.floatToRawIntBits(f))
            assertTrue(src.contains("uintBitsToFloat(0x${bits}u)"), "constant $f")
        }
        assertTrue(src.contains("local_size_x = 16, local_size_y = 16"))
    }

    @Test
    fun `frame layout sections`() {
        assertEquals(0, FrameLayout.offset(FrameLayout.COLOR, 100))
        assertEquals(300, FrameLayout.offset(FrameLayout.ALPHA, 100))
        assertEquals(400, FrameLayout.offset(FrameLayout.FINAL_T, 100))
        assertEquals(500, FrameLayout.offset(FrameLayout.FLOATS_PER_PIXEL, 100))
        assertEquals(5 * 4 * 320 * 240, FrameLayout.imageBytes(320 * 240))
        assertThrows<IllegalArgumentException> { FrameLayout.imageBytes(0) }
        assertThrows<IllegalArgumentException> { FrameLayout.offset(6, 1) }
        assertThrows<ArithmeticException> { FrameLayout.imageBytes(Int.MAX_VALUE / 2) }
    }

    private fun assertSame(cpu: RenderBuffers, model: CompositeModel, what: String) {
        assertArrayEquals(cpu.color, model.color, what)
        assertArrayEquals(cpu.alpha, model.alpha, what)
        assertArrayEquals(cpu.finalT, model.finalT, what)
        assertArrayEquals(cpu.contributors, model.contributors, what)
    }

    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics, pose: CameraPose): RenderBuffers =
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
}
