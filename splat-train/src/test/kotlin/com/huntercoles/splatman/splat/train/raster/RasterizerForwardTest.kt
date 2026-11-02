package com.huntercoles.splatman.splat.train.raster

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class RasterizerForwardTest {

    /** One splat for [cloud]: world position, isotropic std (m), opacity 0..1, rgb 0..1. */
    private class S(val x: Float, val y: Float, val z: Float, val std: Float, val opacity: Float, val rgb: FloatArray)

    private val c0 = 0.28209479177387814

    private fun cloud(splats: List<S>): SplatCloud {
        val n = splats.size
        return SplatCloud(
            count = n,
            shDegree = 0,
            positions = FloatArray(3 * n) { val s = splats[it / 3]; floatArrayOf(s.x, s.y, s.z)[it % 3] },
            logScales = FloatArray(3 * n) { ln(splats[it / 3].std.toDouble()).toFloat() },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n) { val o = splats[it].opacity.toDouble(); ln(o / (1 - o)).toFloat() },
            shDc = FloatArray(3 * n) { ((splats[it / 3].rgb[it % 3] - 0.5) / c0).toFloat() },
            shRest = FloatArray(0),
        )
    }

    /** Camera at the world origin looking down world -Z (`T_W_Cgl` = identity): world (0, 0, -z) is Ccv (0, 0, z). */
    private val identity = CameraPose(Quat(1f, 0f, 0f, 0f), Vec3(0f, 0f, 0f))

    private fun render(cloud: SplatCloud, k: CameraIntrinsics, threads: Int = 1): RenderBuffers {
        val out = RenderBuffers(k.width, k.height)
        Rasterizer(threads).use { it.forward(cloud, k, identity, out) }
        return out
    }

    @Test
    fun `one splat at the image centre gives alpha = opacity and its colour at the centre pixel`() {
        val k = CameraIntrinsics(fx = 100f, fy = 100f, cx = 31f, cy = 23f, width = 64, height = 48)
        val rgb = floatArrayOf(0.8f, 0.3f, 0.1f)
        val out = render(cloud(listOf(S(0f, 0f, -2f, 0.05f, 0.6f, rgb))), k)

        val p = 23 * 64 + 31
        assertEquals(0.6f, out.alpha[p], 1e-4f)
        assertEquals(0.4f, out.finalT[p], 1e-4f)
        assertEquals(1, out.contributors[p])
        for (ch in 0 until 3) {
            // Over black the stored colour is premultiplied: rgb * opacity; unpremultiplied it is the splat's rgb.
            assertEquals(rgb[ch] * 0.6f, out.color[3 * p + ch], 1e-4f)
            assertEquals(rgb[ch], out.color[3 * p + ch] / out.alpha[p], 1e-4f)
        }
        // Off-centre pixels are fainter, and a far corner is untouched.
        assertTrue(out.alpha[p + 1] < out.alpha[p])
        assertEquals(0f, out.alpha[0])
        assertEquals(1f, out.finalT[0])
    }

    @Test
    fun `a splat behind the camera draws nothing`() {
        val k = CameraIntrinsics(fx = 100f, fy = 100f, cx = 31f, cy = 23f, width = 64, height = 48)
        val out = render(cloud(listOf(S(0f, 0f, 2f, 0.5f, 0.9f, floatArrayOf(1f, 1f, 1f)))), k)

        assertEquals(0, out.radii[0])
        assertEquals(0, out.tileStart[out.tileCount])
        assertTrue(out.color.all { it == 0f })
        assertTrue(out.alpha.all { it == 0f })
        assertTrue(out.finalT.all { it == 1f })
        assertTrue(out.contributors.all { it == 0 })
    }

    @Test
    fun `two overlapping splats composite in depth order whatever the array order`() {
        val k = CameraIntrinsics(fx = 100f, fy = 100f, cx = 31f, cy = 23f, width = 64, height = 48)
        val nearRed = S(0f, 0f, -2f, 0.05f, 0.5f, floatArrayOf(1f, 0f, 0f))
        val farBlue = S(0f, 0f, -4f, 0.1f, 0.8f, floatArrayOf(0f, 0f, 1f))
        val a = render(cloud(listOf(nearRed, farBlue)), k)
        val b = render(cloud(listOf(farBlue, nearRed)), k)

        // By hand at the centre: red 1 * 0.5, then blue 1 * 0.8 * (1 - 0.5); T = 0.5 * 0.2.
        val p = 23 * 64 + 31
        for (out in listOf(a, b)) {
            assertEquals(0.5f, out.color[3 * p], 1e-4f)
            assertEquals(0f, out.color[3 * p + 1], 1e-4f)
            assertEquals(0.4f, out.color[3 * p + 2], 1e-4f)
            assertEquals(0.9f, out.alpha[p], 1e-4f)
            assertEquals(0.1f, out.finalT[p], 1e-4f)
            assertEquals(2, out.contributors[p])
        }
        assertArrayEquals(a.color, b.color)
        assertArrayEquals(a.finalT, b.finalT)
    }

    @Test
    fun `a splat spanning a tile edge draws as the same splat shifted inside one tile`() {
        // std 0.03 m at 2 m with fx 100 is 1.5 px; with the 0.3 px^2 low-pass and the 3DGS discriminant floor 0.1 the
        // radius is ceil(3 sqrt(2.55 + sqrt(0.1))) = ceil(5.08) = 6, so the footprint spans u 10..22 on the edge.
        val splat = cloud(listOf(S(0f, 0f, -2f, 0.03f, 0.7f, floatArrayOf(0.2f, 0.9f, 0.4f))))
        val onEdge = render(splat, CameraIntrinsics(100f, 100f, cx = 16f, cy = 16f, width = 48, height = 48))
        val inside = render(splat, CameraIntrinsics(100f, 100f, cx = 8f, cy = 8f, width = 48, height = 48))

        assertEquals(6, onEdge.radii[0])
        // On the corner of tiles 0, 1, 3, 4 (3 tiles across), versus tile 0 alone.
        val edgeTiles = (0 until onEdge.tileCount).filter { onEdge.tileStart[it + 1] > onEdge.tileStart[it] }
        val insideTiles = (0 until inside.tileCount).filter { inside.tileStart[it + 1] > inside.tileStart[it] }
        assertEquals(listOf(0, 1, 3, 4), edgeTiles)
        assertEquals(listOf(0), insideTiles)

        val shift = 8
        for (y in 0 until 48 - shift) for (x in 0 until 48 - shift) {
            val pe = (y + shift) * 48 + x + shift
            val pi = y * 48 + x
            assertEquals(inside.finalT[pi], onEdge.finalT[pe], "T at ($x, $y)")
            assertEquals(inside.contributors[pi], onEdge.contributors[pe], "contributors at ($x, $y)")
            for (ch in 0 until 3) assertEquals(inside.color[3 * pi + ch], onEdge.color[3 * pe + ch], "rgb at ($x, $y)")
        }
        assertEquals(0.7f, onEdge.alpha[16 * 48 + 16], 1e-4f)
    }

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
            count = n,
            shDegree = 0,
            positions = positions,
            logScales = logScales,
            rotations = rotations,
            opacityLogits = FloatArray(n) { rng.nextFloat() * 6f - 3f },
            shDc = FloatArray(3 * n) { rng.nextFloat() * 4f - 2f },
            shRest = FloatArray(0),
        )
    }

    private val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)

    @Test
    fun `1 thread and 8 threads give bit-identical images`() {
        val scene = randomScene(3000, qvga, seed = 42)
        val one = render(scene, qvga, threads = 1)
        val eight = render(scene, qvga, threads = 8)

        assertTrue(one.alpha.count { it > 0.5f } > 1000, "the scene should cover the image")
        assertArrayEquals(one.color, eight.color)
        assertArrayEquals(one.alpha, eight.alpha)
        assertArrayEquals(one.finalT, eight.finalT)
        assertArrayEquals(one.contributors, eight.contributors)
        assertArrayEquals(one.tileStart, eight.tileStart)
    }

    @Test
    fun `hardening - 50k splats at 320x240, reported not asserted`() {
        val scene = randomScene(50_000, qvga, seed = 7)
        val out = RenderBuffers(qvga.width, qvga.height)
        Rasterizer().use { raster ->
            repeat(5) { raster.forward(scene, qvga, identity, out) }
            val times = LongArray(10) {
                val t0 = System.nanoTime()
                raster.forward(scene, qvga, identity, out)
                System.nanoTime() - t0
            }
            times.sort()
            println(
                "RasterizerForward 50k splats 320x240, ${raster.threads} threads: median " +
                    "${times[times.size / 2] / 1_000_000.0} ms, best ${times[0] / 1_000_000.0} ms, " +
                    "${out.tileStart[out.tileCount]} tile entries",
            )
        }
    }
}
