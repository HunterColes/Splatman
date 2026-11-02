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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stage 3 of the GPU backward pass, run as its fp32 model ([SplatBackwardModel]), against `Rasterizer.backward` (the
 * CPU in Double; its gradient check, `RasterizerGradientTest`, stays the ground truth): every parameter group and
 * viewGradNorms within the ADR 0010 relative L2 error of 1e-3, from the CPU's screen-space sums and from the fp32
 * models of stages 1 and 2. dL/dColor is the L1 gradient against a perturbed render.
 */
class SplatBackwardModelTest {
    @Test
    fun `from the CPU's screen-space sums every group is within 1e-3 of the CPU`() {
        for ((name, scene) in scenes()) {
            val (cloud, k) = scene
            val (fwd, want) = cpu(cloud, k)
            val g2 = FloatArray(Grads2dLayout.ENTRY * cloud.count).also { out ->
                want.screenGrads2d().forEachIndexed { j, v -> out[j] = v.toFloat() }
            }
            check(name, want, SplatBackwardModel.run(cloud, fwd.radii, g2, k, pose), cloud.count)
        }
    }

    @Test
    fun `the fp32 pipeline (replay, gather, per splat) is within 1e-3 of the CPU`() {
        for ((name, scene) in scenes()) {
            val (cloud, k) = scene
            val (fwd, want) = cpu(cloud, k)
            val dL = l1Gradient(cloud, k, fwd)
            val g2 = Grads2dModel.gather(Grads2dModel.Lists.of(fwd), Grads2dModel.replay(fwd, dL))
            check("$name pipeline", want, SplatBackwardModel.run(cloud, fwd.radii, g2, k, pose), cloud.count)
        }
    }

    @Test
    fun `culled splats and a zero quaternion get the CPU's zeros`() {
        val k = small
        val base = randomScene(50, k, 42)
        val rotations = base.rotations.copyOf().also { it.fill(0f, 4 * 3, 4 * 4) }
        // Splat 5 behind the camera: culled at the near plane.
        val positions = base.positions.copyOf().also { it[3 * 5 + 2] = 1f }
        val cloud = like(base, positions = positions, rotations = rotations)
        val (fwd, want) = cpu(cloud, k)
        assertEquals(0, fwd.radii[5])
        assertTrue(fwd.radii[3] > 0)
        val g2 = FloatArray(Grads2dLayout.ENTRY * cloud.count).also { out ->
            want.screenGrads2d().forEachIndexed { j, v -> out[j] = v.toFloat() }
        }
        val got = SplatBackwardModel.run(cloud, fwd.radii, g2, k, pose)
        val n = cloud.count
        for (s in 0 until GradsLayout.FLOATS_PER_SPLAT) {
            val width = width(s)
            if (width == 0) continue
            val at = GradsLayout.floatOffset(s, n)
            for (j in 0 until width) assertEquals(0f, got[at + width * 5 + j], "culled, section $s")
        }
        for (j in 0 until 4) {
            assertEquals(0f, got[GradsLayout.floatOffset(GradsLayout.ROTATIONS, n) + 4 * 3 + j])
            assertEquals(0f, want.rotations[4 * 3 + j])
        }
        assertTrue(got.all { it.isFinite() })
    }

    @Test
    fun `layout puts the gradients at the cloud's offsets, then viewGradNorms`() {
        val c = 100
        assertEquals(CloudLayout.floatOffset(CloudLayout.POSITIONS, c), GradsLayout.floatOffset(GradsLayout.POSITIONS, c))
        assertEquals(300, GradsLayout.floatOffset(GradsLayout.LOG_SCALES, c))
        assertEquals(600, GradsLayout.floatOffset(GradsLayout.ROTATIONS, c))
        assertEquals(1000, GradsLayout.floatOffset(GradsLayout.OPACITY_LOGITS, c))
        assertEquals(1100, GradsLayout.floatOffset(GradsLayout.SH_DC, c))
        assertEquals(1400, GradsLayout.floatOffset(GradsLayout.VIEW_GRAD_NORMS, c))
        assertEquals(15, GradsLayout.FLOATS_PER_SPLAT)
        assertEquals(4 * 15 * c, GradsLayout.bytes(c))
        assertEquals(4 * 15, GradsLayout.bytes(0))
        assertThrows<IllegalArgumentException> { GradsLayout.floatOffset(GradsLayout.FLOATS_PER_SPLAT, c) }
        assertThrows<IllegalArgumentException> { GradsLayout.bytes(-1) }
        assertThrows<ArithmeticException> { GradsLayout.bytes(Int.MAX_VALUE / 15) }
        // Section widths add up to the record.
        assertEquals(GradsLayout.FLOATS_PER_SPLAT, (0 until GradsLayout.FLOATS_PER_SPLAT).sumOf { width(it) })
    }

    @Test
    fun `kernel bindings, uniforms and constants`() {
        val src = BackwardSplatShaders.SPLAT
        val bindings = Regex("""binding = (\d+)""").findAll(src).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(0, 1, 2, 3), bindings)
        assertTrue(bindings.size <= GpuSupport.MIN_SSBO_BINDINGS)
        assertTrue(!src.contains("atomic"))
        assertTrue(src.startsWith("#version 310 es\n"))
        assertTrue(src.contains("local_size_x = ${BackwardSplatShaders.LOCAL}"))
        for (u in listOf("n", "capacity", "outCapacity", "camX", "camY", "camZ", "fx", "fy", "halfW", "halfH") +
            (0 until 9).map { "r$it" }) {
            assertTrue(Regex("""uniform (uint|float) [^;]*\b$u\b""").containsMatchIn(src), "uniform $u")
        }
        assertTrue(src.contains("${GradsLayout.VIEW_GRAD_NORMS}u * outCapacity"))
        assertTrue(src.contains("${CloudLayout.SH_DC}u * capacity"))
    }

    /** Floats per splat of the section starting at [s], 0 inside a section. */
    private fun width(s: Int): Int = when (s) {
        GradsLayout.POSITIONS, GradsLayout.LOG_SCALES, GradsLayout.SH_DC -> 3
        GradsLayout.ROTATIONS -> 4
        GradsLayout.OPACITY_LOGITS, GradsLayout.VIEW_GRAD_NORMS -> 1
        else -> 0
    }

    private fun check(name: String, want: SplatGrads, got: FloatArray, n: Int) {
        val groups = listOf(
            Triple("positions", GradsLayout.POSITIONS, want.positions.copyOf(3 * n)),
            Triple("logScales", GradsLayout.LOG_SCALES, want.logScales.copyOf(3 * n)),
            Triple("rotations", GradsLayout.ROTATIONS, want.rotations.copyOf(4 * n)),
            Triple("opacity", GradsLayout.OPACITY_LOGITS, want.opacityLogits.copyOf(n)),
            Triple("shDc", GradsLayout.SH_DC, want.shDc.copyOf(3 * n)),
            Triple("viewGradNorms", GradsLayout.VIEW_GRAD_NORMS, want.viewGradNorms.copyOf(n)),
        )
        for ((group, section, w) in groups) {
            val at = GradsLayout.floatOffset(section, n)
            val err = relativeL2(w, got.copyOfRange(at, at + w.size))
            assertTrue(err <= TOLERANCE, "$name $group: relative L2 error $err")
        }
    }

    private fun relativeL2(want: FloatArray, got: FloatArray): Double {
        var diff = 0.0
        var norm = 0.0
        for (i in want.indices) {
            val d = got[i].toDouble() - want[i]
            diff += d * d
            norm += want[i].toDouble() * want[i]
        }
        assertTrue(norm > 0.0, "group has no gradient")
        return if (diff.isNaN()) Double.POSITIVE_INFINITY else sqrt(diff / norm)
    }

    /** The CPU forward pass and its backward for the L1 gradient against a perturbed render. */
    private fun cpu(cloud: SplatCloud, k: CameraIntrinsics): Pair<RenderBuffers, SplatGrads> {
        val fwd = RenderBuffers(k.width, k.height)
        val grads = SplatGrads()
        Rasterizer(1).use {
            it.forward(cloud, k, pose, fwd)
            it.backward(cloud, k, pose, fwd, l1Gradient(cloud, k, fwd), grads)
        }
        return fwd to grads
    }

    /** sign(render - target) / size (`CpuBackend.l1`), the target a render of [cloud] with jittered centres and colours. */
    private fun l1Gradient(cloud: SplatCloud, k: CameraIntrinsics, fwd: RenderBuffers): FloatArray {
        val rng = Random(7)
        val target = RenderBuffers(k.width, k.height)
        val jittered = like(
            cloud,
            positions = FloatArray(cloud.positions.size) { cloud.positions[it] + (rng.nextFloat() - 0.5f) * 0.02f },
            shDc = FloatArray(cloud.shDc.size) { cloud.shDc[it] + (rng.nextFloat() - 0.5f) * 0.5f },
        )
        Rasterizer(1).use { it.forward(jittered, k, pose, target) }
        val size = 3 * k.width * k.height
        return FloatArray(size) { (fwd.color[it] - target.color[it]).sign / size }
    }

    private fun like(
        c: SplatCloud, positions: FloatArray = c.positions, rotations: FloatArray = c.rotations, shDc: FloatArray = c.shDc,
    ) = SplatCloud(
        count = c.count, shDegree = 0, positions = positions, logScales = c.logScales, rotations = rotations,
        opacityLogits = c.opacityLogits, shDc = shDc, shRest = FloatArray(0),
    )

    private fun scenes() = listOf(
        "50 @ 64x64" to (randomScene(50, small, 42) to small),
        "2000 @ 320x240" to (randomScene(2000, qvga, 42) to qvga),
    )

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

    private companion object {
        /** ADR 0010: gradients per group, relative L2 error. */
        const val TOLERANCE = 1e-3
    }
}
