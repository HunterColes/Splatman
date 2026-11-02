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
import com.huntercoles.splatman.splat.train.raster.Lens
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
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * GPU projection and binning against the CPU `Rasterizer.forward` on the seeded scenes of ADR 0010, within its
 * tolerances: means2d 1e-3 px, depths 1e-5 relative, conics 1e-4 relative, radii within 1 px, tile lists identical
 * on >= 99.9% of entries. Skips without GLES 3.1. QA: ./gradlew :splat-gpu:connectedDebugAndroidTest; each scene
 * logs one `parity` line (tag ProjectionParity).
 */
@RunWith(AndroidJUnit4::class)
class ProjectionParityTest {
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
    fun smallScene50SplatsAt64x64() = parity("50 @ 64x64", randomScene(50, small, 42), small, null)

    @Test
    fun scene2000SplatsAt320x240() = parity("2000 @ 320x240", randomScene(2000, qvga, 42), qvga, null)

    @Test
    fun scene2000SplatsAt320x240WithLens() =
        parity("2000 @ 320x240 lens", randomScene(2000, qvga, 42), qvga, Lens(1.03f, -0.05f))

    @Test
    fun buffersAreReusedAndShrinkingCloudsStayCorrect() {
        val big = randomScene(2000, qvga, 42)
        val little = randomScene(50, qvga, 7)
        val cpuLittle = cpu(little, qvga, null)
        gl!!.run {
            GpuCloud(this).use { cloud ->
                GpuBinning(this).use { binning ->
                    GpuRenderBuffers(this, qvga.width, qvga.height).use { out ->
                        cloud.upload(big)
                        binning.forward(cloud, qvga, pose, out)
                        val ids = listOf(cloud.buffer.id, out.radii.id, out.tileSplats.id)
                        val sizes = listOf(cloud.buffer.sizeBytes, out.radii.sizeBytes, out.tileSplats.sizeBytes)
                        cloud.upload(little)
                        binning.forward(cloud, qvga, pose, out)
                        assertEquals(ids, listOf(cloud.buffer.id, out.radii.id, out.tileSplats.id))
                        assertEquals(sizes, listOf(cloud.buffer.sizeBytes, out.radii.sizeBytes, out.tileSplats.sizeBytes))
                        val got = Snapshot.of(out)
                        assertEquals(cpuLittle.tileStart[cpuLittle.tileCount], got.tileStart[out.tileCount])
                        assertTrue(tileMatch(cpuLittle, got) >= MIN_TILE_MATCH)
                    }
                }
            }
        }
    }

    @Test
    fun emptyCloudBinsNothing() {
        val empty = SplatCloud(0, 0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        val got = gpu(empty, small, null)
        assertEquals(0, got.total)
        assertTrue(got.tileStart.all { it == 0 })
    }

    private fun parity(name: String, cloud: SplatCloud, k: CameraIntrinsics, lens: Lens?) {
        val cpu = cpu(cloud, k, lens)
        val gpu = gpu(cloud, k, lens)
        val n = cloud.count
        var visible = 0
        var meanErr = 0.0
        var depthErr = 0.0
        var conicErr = 0.0
        var radiusErr = 0
        var colorErr = 0.0
        for (i in 0 until n) {
            radiusErr = max(radiusErr, abs(gpu.radii[i] - cpu.radii[i]))
            depthErr = max(depthErr, rel(gpu.depths[i], cpu.depths[i]))
            if (gpu.radii[i] <= 0 || cpu.radii[i] <= 0) continue
            visible++
            for (a in 0 until 2) meanErr = max(meanErr, abs(gpu.means2d[2 * i + a] - cpu.means2d[2 * i + a]).toDouble())
            // Relative to the conic's size: b may be near 0 where a and c are not.
            val scale = max(abs(cpu.conics[3 * i]), abs(cpu.conics[3 * i + 2]))
            for (a in 0 until 3) {
                conicErr = max(conicErr, abs(gpu.conics[3 * i + a] - cpu.conics[3 * i + a]).toDouble() / scale)
                colorErr = max(colorErr, abs(gpu.colors[3 * i + a] - cpu.colors[3 * i + a]).toDouble())
            }
            colorErr = max(colorErr, abs(gpu.opacities[i] - cpu.opacities[i]).toDouble())
        }
        val match = tileMatch(cpu, gpu)
        Log.i(
            TAG,
            "parity $name: visible $visible/$n, means2d ${"%.2e".format(meanErr)} px, depth ${"%.2e".format(depthErr)}, " +
                "conic ${"%.2e".format(conicErr)}, radius $radiusErr px, colour/opacity ${"%.2e".format(colorErr)}, " +
                "entries ${gpu.total}/${cpu.tileStart[cpu.tileCount]}, tile lists ${"%.5f".format(match)}",
        )
        assertTrue("$name: too few visible splats ($visible)", visible > n / 2)
        assertTrue("$name: means2d off by $meanErr px", meanErr <= 1e-3)
        assertTrue("$name: depth off by $depthErr relative", depthErr <= 1e-5)
        assertTrue("$name: conic off by $conicErr relative", conicErr <= 1e-4)
        assertTrue("$name: radius off by $radiusErr px", radiusErr <= 1)
        assertTrue("$name: colour or opacity off by $colorErr", colorErr <= 1e-5)
        assertTrue("$name: tile lists match on only $match of entries", match >= MIN_TILE_MATCH)
    }

    /** Matching entries over all entries: tile by tile, position by position, against the longer of the two lists. */
    private fun tileMatch(cpu: RenderBuffers, gpu: Snapshot): Double {
        var same = 0L
        var all = 0L
        for (t in 0 until cpu.tileCount) {
            val c0 = cpu.tileStart[t]
            val cn = cpu.tileStart[t + 1] - c0
            val g0 = gpu.tileStart[t]
            val gn = gpu.tileStart[t + 1] - g0
            for (j in 0 until min(cn, gn)) if (cpu.tileSplats[c0 + j] == gpu.tileSplats[g0 + j]) same++
            all += max(cn, gn)
        }
        return if (all == 0L) 1.0 else same.toDouble() / all
    }

    private fun rel(got: Float, want: Float): Double = abs(got - want).toDouble() / max(abs(want), 1e-6f)

    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics, lens: Lens?): RenderBuffers =
        RenderBuffers(k.width, k.height).also { out -> Rasterizer(1).use { it.forward(cloud, k, pose, out, lens) } }

    private fun gpu(cloud: SplatCloud, k: CameraIntrinsics, lens: Lens?): Snapshot = gl!!.run {
        GpuCloud(this).use { c ->
            GpuBinning(this).use { b ->
                GpuRenderBuffers(this, k.width, k.height).use { out ->
                    c.upload(cloud)
                    b.forward(c, k, pose, out, lens)
                    Snapshot.of(out)
                }
            }
        }
    }

    /** The GPU buffers read back. */
    private class Snapshot(
        val radii: IntArray, val depths: FloatArray, val means2d: FloatArray, val conics: FloatArray,
        val colors: FloatArray, val opacities: FloatArray, val tileStart: IntArray, val tileSplats: IntArray,
        val total: Int,
    ) {
        companion object {
            fun of(out: GpuRenderBuffers): Snapshot {
                val n = out.splatCount
                val total = out.tileEntries
                return Snapshot(
                    out.radii.downloadInts(n), out.depths.downloadFloats(n), out.means2d.downloadFloats(2 * n),
                    out.conics.downloadFloats(3 * n), out.colors.downloadFloats(3 * n), out.opacities.downloadFloats(n),
                    out.tileStart.downloadInts(out.tileCount + 1), out.tileSplats.downloadInts(total), total,
                )
            }
        }
    }

    private companion object {
        const val TAG = "ProjectionParity"
        const val MIN_TILE_MATCH = 0.999

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
