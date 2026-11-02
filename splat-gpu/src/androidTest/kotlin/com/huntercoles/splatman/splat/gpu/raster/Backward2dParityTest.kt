package com.huntercoles.splatman.splat.gpu.raster

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.GpuSupport
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import com.huntercoles.splatman.splat.train.raster.SplatGrads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * `GpuGrads2d` (tile replay, then per-splat gather) against the CPU's splatGrads2d (`Rasterizer(1).backward`, read
 * through `SplatGrads.screenGrads2d`) on the seeded scenes of ADR 0010: relative L2 error <= 1e-3 per group (mean2d,
 * conic, colour, opacity). Two runs per scene: "isolated" replays the CPU's own forward pass uploaded to the GPU (the
 * backward kernels alone); "pipeline" replays the GPU forward pass (`GpuRasterizer.forward`). Skips without GLES 3.1.
 * QA: ./gradlew :splat-gpu:connectedDebugAndroidTest; each run logs one `parity` line with the four errors (tag
 * Backward2dParity).
 */
@RunWith(AndroidJUnit4::class)
class Backward2dParityTest {
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
        parity("50 @ 64x64", randomScene(50, small, 42), small)
    }

    @Test
    fun scene2000SplatsAt320x240() {
        parity("2000 @ 320x240", randomScene(2000, qvga, 42), qvga)
    }

    @Test
    fun aRunIsDeterministicAndReusesItsBuffers() {
        val gl = gl!!
        val big = randomScene(2000, qvga, 42)
        val little = randomScene(50, qvga, 7)
        val dL = dLdColor(qvga, 42)
        GpuRasterizer(gl).use { raster ->
            GpuGrads2d(gl).use { grads ->
                GpuFrame(gl, qvga.width, qvga.height).use { frame ->
                    withSsbo(dL) { dLdColor ->
                        raster.forward(big, qvga, pose, frame)
                        grads.run(frame, dLdColor)
                        val first = grads.readSplatGrads2d()
                        raster.forward(little, qvga, pose, frame)
                        grads.run(frame, dLdColor)
                        assertEquals(Grads2dLayout.ENTRY * 50, grads.readSplatGrads2d().size)
                        raster.forward(big, qvga, pose, frame)
                        grads.run(frame, dLdColor)
                        val again = grads.readSplatGrads2d()
                        for (i in first.indices) {
                            assertEquals("float $i", first[i].toRawBits(), again[i].toRawBits())
                        }
                    }
                }
            }
        }
    }

    private fun parity(name: String, cloud: SplatCloud, k: CameraIntrinsics) {
        val gl = gl!!
        val fwd = RenderBuffers(k.width, k.height)
        val dL = dLdColor(k, 42)
        val cpuGrads = SplatGrads()
        Rasterizer(1).use {
            it.forward(cloud, k, pose, fwd)
            it.backward(cloud, k, pose, fwd, dL, cpuGrads)
        }
        val want = cpuGrads.screenGrads2d()

        GpuGrads2d(gl).use { grads ->
            GpuFrame(gl, k.width, k.height).use { frame ->
                withSsbo(dL) { dLdColor ->
                    upload(fwd, frame)
                    grads.run(frame, dLdColor)
                    check("$name isolated", want, grads.readSplatGrads2d())

                    GpuRasterizer(gl).use { it.forward(cloud, k, pose, frame) }
                    grads.run(frame, dLdColor)
                    check("$name pipeline", want, grads.readSplatGrads2d())
                }
            }
        }
    }

    private fun check(name: String, want: DoubleArray, got: FloatArray) {
        assertEquals(want.size, got.size)
        val errors = GROUPS.map { (group, slots) -> group to relativeL2(want, got, slots) }
        Log.i(TAG, "parity $name: " + errors.joinToString { (g, e) -> "$g ${"%.2e".format(e)}" })
        for ((group, e) in errors) assertTrue("$name $group: relative L2 error $e", e <= TOLERANCE)
    }

    private fun relativeL2(want: DoubleArray, got: FloatArray, slots: IntRange): Double {
        var diff = 0.0
        var norm = 0.0
        for (i in 0 until want.size / Grads2dLayout.ENTRY) for (j in slots) {
            val w = want[Grads2dLayout.ENTRY * i + j]
            val d = got[Grads2dLayout.ENTRY * i + j] - w
            diff += d * d
            norm += w * w
        }
        assertTrue("group $slots has no gradient", norm > 0.0)
        return if (diff.isNaN()) Double.POSITIVE_INFINITY else sqrt(diff / norm)
    }

    /** The CPU forward pass into [frame]: projection, tile lists, finalT and contributors (what the replay reads). */
    private fun upload(fwd: RenderBuffers, frame: GpuFrame) = gl!!.run {
        val r = frame.render
        val n = fwd.splatCount
        val entries = fwd.tileStart[fwd.tileCount]
        r.ensureSplats(n)
        r.ensureEntries(entries)
        r.tileEntries = entries
        r.radii.upload(fwd.radii.copyOf(n))
        r.depths.upload(fwd.depths.copyOf(n))
        r.means2d.upload(fwd.means2d.copyOf(2 * n))
        r.conics.upload(fwd.conics.copyOf(3 * n))
        r.colors.upload(fwd.colors.copyOf(3 * n))
        r.opacities.upload(fwd.opacities.copyOf(n))
        r.tileStart.upload(fwd.tileStart)
        if (entries > 0) r.tileSplats.upload(fwd.tileSplats.copyOf(entries))
        val finalTAt = FrameLayout.offset(FrameLayout.FINAL_T, frame.pixels) * Std430.SCALAR_BYTES
        frame.image.upload(fwd.finalT, finalTAt)
        frame.contributors.upload(fwd.contributors)
    }

    private fun withSsbo(data: FloatArray, body: (Ssbo) -> Unit) {
        val buf = gl!!.run { ssbo(Std430.arrayBytes(Std430Type.FLOAT, data.size)).also { it.upload(data) } }
        try {
            body(buf)
        } finally {
            gl!!.run { buf.close() }
        }
    }

    private companion object {
        const val TAG = "Backward2dParity"

        /** ADR 0010: gradients per group, relative L2 error. */
        const val TOLERANCE = 1e-3

        val GROUPS = listOf(
            "mean2d" to Grads2dLayout.U..Grads2dLayout.V,
            "conic" to Grads2dLayout.CA..Grads2dLayout.CC,
            "colour" to Grads2dLayout.R..Grads2dLayout.R + 2,
            "opacity" to Grads2dLayout.OP..Grads2dLayout.OP,
        )

        val small = CameraIntrinsics(fx = 56f, fy = 56f, cx = 31.5f, cy = 31.5f, width = 64, height = 64)
        val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)

        /** A small turn about +Y, so the world -> camera rotation is not the identity. */
        val pose = CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.05f), Vec3(0.02f, -0.01f, 0.03f))

        /** Seeded dL/dColor in [-1, 1], with every 5th pixel zero (skipped by the replay). */
        fun dLdColor(k: CameraIntrinsics, seed: Int): FloatArray {
            val rng = Random(seed)
            return FloatArray(3 * k.width * k.height) { if ((it / 3) % 5 == 0) 0f else rng.nextFloat() * 2f - 1f }
        }

        /** As in ForwardParityTest: [n] splats in front of the camera, spread over the image, depths 2..6 m. */
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
