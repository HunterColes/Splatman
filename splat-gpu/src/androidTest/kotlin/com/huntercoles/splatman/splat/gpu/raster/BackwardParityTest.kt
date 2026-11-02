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
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * `GpuRasterizer.backward` (tile replay, gather, per-splat chain rule) against `Rasterizer(1).backward` on the seeded
 * scenes of ADR 0010, for a fixed dL/dColor: the CPU's L1 gradient against a render of the cloud with jittered centres
 * and colours. Every parameter group (positions, log-scales, rotations, opacity logits, SH dc) and viewGradNorms
 * within a relative L2 error of 1e-3. Skips without GLES 3.1. QA: ./gradlew :splat-gpu:connectedDebugAndroidTest;
 * each scene logs one `parity` line with the six errors (tag BackwardParity).
 */
@RunWith(AndroidJUnit4::class)
class BackwardParityTest {
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
    fun aBackwardIsDeterministicAndReusesItsBuffers() {
        val gl = gl!!
        val big = randomScene(2000, qvga, 42)
        val little = randomScene(50, qvga, 7)
        val (_, dL) = cpu(big, qvga)
        GpuRasterizer(gl).use { raster ->
            GpuGrads(gl).use { grads ->
                GpuFrame(gl, qvga.width, qvga.height).use { frame ->
                    withSsbo(dL) { dLdColor ->
                        val first = SplatGrads()
                        raster.forward(big, qvga, pose, frame)
                        raster.backward(big, qvga, pose, frame, dLdColor, grads)
                        grads.readInto(first)
                        raster.forward(little, qvga, pose, frame)
                        raster.backward(little, qvga, pose, frame, dLdColor, grads)
                        assertEquals(50, grads.count)
                        val again = SplatGrads()
                        raster.forward(big, qvga, pose, frame)
                        raster.backward(big, qvga, pose, frame, dLdColor, grads)
                        grads.readInto(again)
                        for ((a, b) in groups(first, 2000).zip(groups(again, 2000))) {
                            for (i in a.second.indices) {
                                assertEquals("${a.first} $i", a.second[i].toRawBits(), b.second[i].toRawBits())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parity(name: String, cloud: SplatCloud, k: CameraIntrinsics) {
        val gl = gl!!
        val (want, dL) = cpu(cloud, k)
        val got = SplatGrads()
        GpuRasterizer(gl).use { raster ->
            GpuGrads(gl).use { grads ->
                GpuFrame(gl, k.width, k.height).use { frame ->
                    withSsbo(dL) { dLdColor ->
                        raster.forward(cloud, k, pose, frame)
                        raster.backward(cloud, k, pose, frame, dLdColor, grads)
                        grads.readInto(got)
                    }
                }
            }
        }
        assertEquals(cloud.count, got.count)
        val n = cloud.count
        val errors = groups(want, n).zip(groups(got, n)).map { (w, g) -> w.first to relativeL2(w.first, w.second, g.second) }
        Log.i(TAG, "parity $name: " + errors.joinToString { (g, e) -> "$g ${"%.2e".format(e)}" })
        for ((group, e) in errors) assertTrue("$name $group: relative L2 error $e", e <= TOLERANCE)
    }

    private fun groups(g: SplatGrads, n: Int) = listOf(
        "positions" to g.positions.copyOf(3 * n),
        "logScales" to g.logScales.copyOf(3 * n),
        "rotations" to g.rotations.copyOf(4 * n),
        "opacity" to g.opacityLogits.copyOf(n),
        "shDc" to g.shDc.copyOf(3 * n),
        "viewGradNorms" to g.viewGradNorms.copyOf(n),
    )

    private fun relativeL2(group: String, want: FloatArray, got: FloatArray): Double {
        var diff = 0.0
        var norm = 0.0
        for (i in want.indices) {
            val d = got[i].toDouble() - want[i]
            diff += d * d
            norm += want[i].toDouble() * want[i]
        }
        assertTrue("$group has no gradient", norm > 0.0)
        return if (diff.isNaN()) Double.POSITIVE_INFINITY else sqrt(diff / norm)
    }

    /** The CPU's gradients of [cloud] and the dL/dColor they were computed for. */
    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics): Pair<SplatGrads, FloatArray> {
        val fwd = RenderBuffers(k.width, k.height)
        val target = RenderBuffers(k.width, k.height)
        val grads = SplatGrads()
        val size = 3 * k.width * k.height
        val dL = FloatArray(size)
        Rasterizer(1).use {
            it.forward(cloud, k, pose, fwd)
            it.forward(jittered(cloud), k, pose, target)
            for (j in 0 until size) dL[j] = (fwd.color[j] - target.color[j]).sign / size
            it.backward(cloud, k, pose, fwd, dL, grads)
        }
        return grads to dL
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
        const val TAG = "BackwardParity"

        /** ADR 0010: gradients per group, relative L2 error. */
        const val TOLERANCE = 1e-3

        val small = CameraIntrinsics(fx = 56f, fy = 56f, cx = 31.5f, cy = 31.5f, width = 64, height = 64)
        val qvga = CameraIntrinsics(fx = 280f, fy = 280f, cx = 159.5f, cy = 119.5f, width = 320, height = 240)

        /** A small turn about +Y, so the world -> camera rotation is not the identity. */
        val pose = CameraPose(Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.05f), Vec3(0.02f, -0.01f, 0.03f))

        /** The L1 target's cloud: [cloud] with seeded jitter on the centres (+-1 cm) and the SH dc (+-0.25). */
        fun jittered(cloud: SplatCloud): SplatCloud {
            val rng = Random(7)
            return SplatCloud(
                count = cloud.count, shDegree = 0,
                positions = FloatArray(cloud.positions.size) { cloud.positions[it] + (rng.nextFloat() - 0.5f) * 0.02f },
                logScales = cloud.logScales, rotations = cloud.rotations, opacityLogits = cloud.opacityLogits,
                shDc = FloatArray(cloud.shDc.size) { cloud.shDc[it] + (rng.nextFloat() - 0.5f) * 0.5f },
                shRest = FloatArray(0),
            )
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
