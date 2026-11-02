package com.huntercoles.splatman.splat.gpu.raster

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.GpuSupport
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * `GpuRasterizer.forward` against the CPU `Rasterizer(1).forward` on the seeded scenes of ADR 0010 and the single-splat
 * golden case of ADR 0009, within ADR 0010's image tolerances: colour (and alpha, finalT) |gpu - cpu| <= 1e-3 on
 * >= 99.9% of pixel channels, <= 2e-2 everywhere, mean <= 1e-4; contributors equal on >= 99.9% of pixels. Skips
 * without GLES 3.1. QA: ./gradlew :splat-gpu:connectedDebugAndroidTest; each case logs one `parity` line with the
 * max and mean errors (tag ForwardParity).
 */
@RunWith(AndroidJUnit4::class)
class ForwardParityTest {
    private var gl: GlesCompute? = null

    @Before
    fun setUp() {
        val support = GpuSupport.probe()
        assumeTrue("GPU unsupported: $support", support is GpuSupport.Supported)
        gl = GlesCompute.create()
    }

    @After
    fun tearDown() {
        gl?.close()
    }

    @Test
    fun smallScene50SplatsAt64x64() {
        parity("50 @ 64x64", randomScene(50, small, 42), small, pose)
    }

    @Test
    fun scene2000SplatsAt320x240() {
        parity("2000 @ 320x240", randomScene(2000, qvga, 42), qvga, pose)
    }

    @Test
    fun goldenSingleSplatCentreAlphaIsOpacity() {
        // ADR 0009 / RasterizerForwardTest: one splat at the image centre, opacity 0.6, colour (0.8, 0.3, 0.1).
        val k = CameraIntrinsics(fx = 100f, fy = 100f, cx = 31f, cy = 23f, width = 64, height = 48)
        val rgb = floatArrayOf(0.8f, 0.3f, 0.1f)
        val cloud = SplatCloud(
            count = 1, shDegree = 0, positions = floatArrayOf(0f, 0f, -2f),
            logScales = FloatArray(3) { ln(0.05f) }, rotations = floatArrayOf(1f, 0f, 0f, 0f),
            opacityLogits = floatArrayOf(ln(0.6f / 0.4f)),
            shDc = FloatArray(3) { ((rgb[it] - 0.5) / C0).toFloat() }, shRest = FloatArray(0),
        )
        val identity = CameraPose(Quat(1f, 0f, 0f, 0f), Vec3(0f, 0f, 0f))
        val got = parity("golden single splat", cloud, k, identity)
        val p = 23 * 64 + 31
        assertEquals(0.6f, got.alpha[p], 1e-4f)
        assertEquals(0.4f, got.finalT[p], 1e-4f)
        assertEquals(1, got.contributors[p])
        for (ch in 0 until 3) assertEquals(rgb[ch] * 0.6f, got.color[3 * p + ch], 1e-4f)
        assertEquals(0f, got.alpha[0], 0f)
        assertEquals(1f, got.finalT[0], 0f)
    }

    @Test
    fun aFrameIsReusedAcrossCloudsAndOverwritten() {
        val big = randomScene(2000, qvga, 42)
        val little = randomScene(50, qvga, 7)
        val want = cpu(little, qvga, pose)
        val got = RenderBuffers(qvga.width, qvga.height)
        GpuRasterizer(gl!!).use { raster ->
            GpuFrame(gl!!, qvga.width, qvga.height).use { frame ->
                raster.forward(big, qvga, pose, frame)
                raster.forward(little, qvga, pose, frame)
                frame.readInto(got)
            }
        }
        val e = Errors.of(want, got)
        Log.i(TAG, "parity reuse 2000 then 50 @ 320x240: $e")
        e.assertWithin("reuse")
    }

    private fun parity(name: String, cloud: SplatCloud, k: CameraIntrinsics, pose: CameraPose): RenderBuffers {
        val want = cpu(cloud, k, pose)
        val got = RenderBuffers(k.width, k.height)
        GpuRasterizer(gl!!).use { raster ->
            GpuFrame(gl!!, k.width, k.height).use { frame ->
                raster.forward(cloud, k, pose, frame)
                frame.readInto(got)
            }
        }
        val e = Errors.of(want, got)
        Log.i(TAG, "parity $name: entries ${want.tileStart[want.tileCount]}, $e")
        e.assertWithin(name)
        return got
    }

    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics, pose: CameraPose): RenderBuffers =
        RenderBuffers(k.width, k.height).also { out -> Rasterizer(1).use { it.forward(cloud, k, pose, out) } }

    /** Max and mean |gpu - cpu| and the share within 1e-3 of colour, alpha and finalT; contributor agreement. */
    private class Errors(
        val color: Stat, val alpha: Stat, val finalT: Stat, val contributorsSame: Double,
    ) {
        class Stat(val max: Double, val mean: Double, val within: Double) {
            override fun toString() =
                "max ${"%.2e".format(max)} mean ${"%.2e".format(mean)} within1e-3 ${"%.5f".format(within)}"

            fun assertWithin(what: String) {
                assertTrue("$what: max error $max", max <= MAX_ERR)
                assertTrue("$what: mean error $mean", mean <= MEAN_ERR)
                assertTrue("$what: only $within within $CLOSE", within >= MIN_SHARE)
            }

            companion object {
                fun of(want: FloatArray, got: FloatArray): Stat {
                    var max = 0.0
                    var sum = 0.0
                    var close = 0
                    for (i in want.indices) {
                        val d = abs(got[i] - want[i]).toDouble()
                        if (!(d <= max)) max = if (d.isNaN()) Double.POSITIVE_INFINITY else d
                        sum += d
                        if (d <= CLOSE) close++
                    }
                    return Stat(max, sum / want.size, close.toDouble() / want.size)
                }
            }
        }

        override fun toString() =
            "colour [$color], alpha [$alpha], finalT [$finalT], contributors same ${"%.5f".format(contributorsSame)}"

        fun assertWithin(name: String) {
            color.assertWithin("$name colour")
            alpha.assertWithin("$name alpha")
            finalT.assertWithin("$name finalT")
            assertTrue("$name: contributors equal on only $contributorsSame", contributorsSame >= MIN_SHARE)
        }

        companion object {
            fun of(want: RenderBuffers, got: RenderBuffers): Errors {
                var same = 0
                for (p in want.contributors.indices) if (want.contributors[p] == got.contributors[p]) same++
                return Errors(
                    Stat.of(want.color, got.color), Stat.of(want.alpha, got.alpha), Stat.of(want.finalT, got.finalT),
                    same.toDouble() / max(1, want.contributors.size),
                )
            }
        }
    }

    private companion object {
        const val TAG = "ForwardParity"
        const val CLOSE = 1e-3
        const val MAX_ERR = 2e-2
        const val MEAN_ERR = 1e-4
        const val MIN_SHARE = 0.999
        const val C0 = 0.28209479177387814

        val small = CameraIntrinsics(fx = 56f, fy = 56f, cx = 31.5f, cy = 31.5f, width = 64, height = 64)
        val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)

        /** A small turn about +Y, so the world -> camera rotation is not the identity. */
        val pose = CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.05f), Vec3(0.02f, -0.01f, 0.03f))

        /** As in RasterizerForwardTest: [n] splats in front of the camera, spread over the image, depths 2..6 m. */
        fun randomScene(n: Int, k: CameraIntrinsics, seed: Int): SplatCloud {
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
}
