package com.huntercoles.splatman.splat.train.raster

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Finite-difference check of [Rasterizer.backward] (ADR 0009): a reference forward pass written here in Double (same
 * maths and thresholds as [Rasterizer.forward], checked against it), central differences with h = 1e-4 on a linear
 * loss `L = sum(w * image)`, so `dL/dImage = w`. Every parameter of every splat must agree with the analytic gradient
 * to relative 1e-3, or absolute 1e-6 where both are ~0.
 */
class RasterizerGradientTest {

    private val c0 = 0.28209479177387814
    private val h = 1e-4

    private val pinhole = doubleArrayOf(1.0, 0.0)

    /** A lens 3 % long with barrel distortion, both strong enough to move every splat by pixels. */
    private val lens = Lens(focalScale = 1.03f, k1 = -0.05f)
    private val lensD = doubleArrayOf(lens.focalScale.toDouble(), lens.k1.toDouble())

    /** 16x16, pixel centres at integers, principal point in the middle. */
    private val k = CameraIntrinsics(fx = 20f, fy = 20f, cx = 7.5f, cy = 7.5f, width = 16, height = 16)

    /** A camera turned 0.2 rad about a tilted axis and moved off the origin, so W and the camera centre matter. */
    private val pose: CameraPose = run {
        val axis = doubleArrayOf(0.3, 1.0, 0.2)
        val len = sqrt(axis.sumOf { it * it })
        val s = sin(0.1) / len
        CameraPose(
            Quat(cos(0.1).toFloat(), (axis[0] * s).toFloat(), (axis[1] * s).toFloat(), (axis[2] * s).toFloat()),
            Vec3(0.1f, -0.05f, 0.2f),
        )
    }

    private val cvPose = FrameConversions.glToCv(pose)

    /** `R_Ccv_W` column-major, as the rasteriser builds it, in Double. */
    private val w: DoubleArray = cvPose.rotation.normalized().toMat3().transpose().values.let { v ->
        DoubleArray(9) { v[it].toDouble() }
    }
    private val centre = doubleArrayOf(
        cvPose.translationM.x.toDouble(), cvPose.translationM.y.toDouble(), cvPose.translationM.z.toDouble(),
    )

    /** Raw parameters in Double; every value is a Float, so the Float cloud holds exactly the same scene. */
    private class Params(
        val positions: DoubleArray,
        val logScales: DoubleArray,
        val rotations: DoubleArray,
        val opacityLogits: DoubleArray,
        val shDc: DoubleArray,
    ) {
        val count get() = opacityLogits.size
        val groups get() = listOf(
            "positions" to positions, "logScales" to logScales, "rotations" to rotations,
            "opacityLogits" to opacityLogits, "shDc" to shDc,
        )

        fun cloud() = SplatCloud(
            count = count, shDegree = 0,
            positions = FloatArray(positions.size) { positions[it].toFloat() },
            logScales = FloatArray(logScales.size) { logScales[it].toFloat() },
            rotations = FloatArray(rotations.size) { rotations[it].toFloat() },
            opacityLogits = FloatArray(count) { opacityLogits[it].toFloat() },
            shDc = FloatArray(shDc.size) { shDc[it].toFloat() },
            shRest = FloatArray(0),
        )
    }

    /** One splat: centre in Ccv (x right, y down, z forward, m), std per axis (m), raw wxyz, opacity, rgb. */
    private class S(val cv: DoubleArray, val std: DoubleArray, val q: DoubleArray, val opacity: Double, val rgb: DoubleArray)

    private fun f(v: Double) = v.toFloat().toDouble()

    private fun params(splats: List<S>): Params {
        val rw = cvPose.rotation.normalized().toMat3().values // R_W_Ccv column-major
        val pos = DoubleArray(3 * splats.size)
        splats.forEachIndexed { i, s ->
            for (r in 0 until 3) {
                pos[3 * i + r] = f(rw[r] * s.cv[0] + rw[3 + r] * s.cv[1] + rw[6 + r] * s.cv[2] + centre[r])
            }
        }
        return Params(
            positions = pos,
            logScales = DoubleArray(3 * splats.size) { f(ln(splats[it / 3].std[it % 3])) },
            rotations = DoubleArray(4 * splats.size) { f(splats[it / 4].q[it % 4]) },
            opacityLogits = DoubleArray(splats.size) { val o = splats[it].opacity; f(ln(o / (1 - o))) },
            shDc = DoubleArray(3 * splats.size) { f((splats[it / 3].rgb[it % 3] - 0.5) / c0) },
        )
    }

    private fun d(vararg v: Double) = v

    /** Five anisotropic, rotated (two quaternions not unit), overlapping splats at different depths, none capped. */
    private val scene = params(
        listOf(
            S(d(0.0, 0.0, 2.0), d(0.25, 0.12, 0.08), d(0.9, 0.2, -0.3, 0.1), 0.7, d(0.8, 0.3, 0.2)),
            S(d(0.3, -0.25, 2.5), d(0.15, 0.3, 0.1), d(0.5, -0.5, 0.4, 0.6), 0.55, d(0.2, 0.7, 0.4)),
            S(d(-0.35, 0.2, 3.0), d(0.3, 0.2, 0.25), d(1.2, 0.1, 0.3, -0.2), 0.8, d(0.3, 0.4, 0.9)),
            S(d(-0.2, -0.3, 1.6), d(0.08, 0.1, 0.15), d(0.3, 0.8, -0.1, 0.4), 0.45, d(0.9, 0.8, 0.1)),
            S(d(0.25, 0.3, 1.8), d(0.12, 0.06, 0.2), d(0.7, -0.2, -0.6, 0.3), 0.6, d(0.5, 0.5, 0.6)),
        ),
    )

    /**
     * Splat 4 sits behind a wall of four 0.95 layers. One splat cannot hide another here: alpha is capped at 0.99, so
     * it leaves T = 0.01; the walk stops only before T would drop under 1e-4, which the fourth layer would do over
     * the whole footprint of splat 4, so splat 4 is never reached.
     */
    private val walled = params(
        List(4) { S(d(0.0, 0.0, 2.0 + 0.1 * it), d(2.0 + 0.1 * it, 2.0 + 0.1 * it, 0.5), d(1.0, 0.0, 0.0, 0.0), 0.95, d(0.6, 0.5, 0.4)) } +
            S(d(0.05, -0.05, 4.0), d(0.05, 0.05, 0.05), d(1.0, 0.0, 0.0, 0.0), 0.9, d(0.9, 0.1, 0.1)),
    )

    private fun weights(n: Int, seed: Int = 7) = Random(seed).let { r -> FloatArray(n) { r.nextFloat() * 2 - 1 } }

    // ---- the Double reference forward pass ----

    /**
     * Renders [p] in Double; [offsets] (2 per splat, px) shift the projected centres, to differentiate by them;
     * [lens] is the focal scale and k1 of [Lens] (1, 0 = the pinhole).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun render(
        p: Params, cam: CameraIntrinsics = k, offsets: DoubleArray? = null, lens: DoubleArray = pinhole,
    ): DoubleArray {
        val n = p.count
        val u = DoubleArray(n)
        val v = DoubleArray(n)
        val conic = DoubleArray(3 * n)
        val depth = DoubleArray(n)
        val opacity = DoubleArray(n)
        val color = DoubleArray(3 * n)
        val visible = mutableListOf<Int>()
        val fx = cam.fx.toDouble() * lens[0]
        val fy = cam.fy.toDouble() * lens[0]
        for (i in 0 until n) {
            val dp = DoubleArray(3) { p.positions[3 * i + it] - centre[it] }
            val t = DoubleArray(3) { r -> w[r] * dp[0] + w[3 + r] * dp[1] + w[6 + r] * dp[2] }
            if (!(t[2] > 0.2)) continue
            var qw = p.rotations[4 * i]; var qx = p.rotations[4 * i + 1]
            var qy = p.rotations[4 * i + 2]; var qz = p.rotations[4 * i + 3]
            val qn = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
            qw /= qn; qx /= qn; qy /= qn; qz /= qn
            val rot = doubleArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qw * qz), 2 * (qx * qz + qw * qy),
                2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx),
                2 * (qx * qz - qw * qy), 2 * (qy * qz + qw * qx), 1 - 2 * (qx * qx + qy * qy),
            )
            val m = DoubleArray(9) { rot[it] * exp(p.logScales[3 * i + it % 3]) }
            val sigma = DoubleArray(9) { (0 until 3).sumOf { j -> m[(it / 3) * 3 + j] * m[(it % 3) * 3 + j] } }
            val xn = t[0] / t[2]
            val yn = t[1] / t[2]
            val dist = 1 + lens[1] * (xn * xn + yn * yn)
            val jac = doubleArrayOf(
                fx * dist / t[2], 0.0, -fx * dist * t[0] / (t[2] * t[2]),
                0.0, fy * dist / t[2], -fy * dist * t[1] / (t[2] * t[2]),
            )
            // T = J W, row-major 2x3; W(r, c) = w[c * 3 + r].
            val tm = DoubleArray(6) { (0 until 3).sumOf { j -> jac[(it / 3) * 3 + j] * w[(it % 3) * 3 + j] } }
            fun quad(r0: Int, r1: Int) = (0 until 3).sumOf { a -> (0 until 3).sumOf { b -> tm[r0 * 3 + a] * sigma[a * 3 + b] * tm[r1 * 3 + b] } }
            val a = quad(0, 0) + 0.3
            val b = quad(0, 1)
            val c = quad(1, 1) + 0.3
            val det = a * c - b * b
            if (!(det > 0)) continue
            val mid = 0.5 * (a + c)
            val radius = ceil(3 * sqrt(mid + sqrt(max(0.1, mid * mid - det))))
            u[i] = fx * xn * dist + cam.cx + (offsets?.get(2 * i) ?: 0.0)
            v[i] = fy * yn * dist + cam.cy + (offsets?.get(2 * i + 1) ?: 0.0)
            if (u[i] + radius < 0 || v[i] + radius < 0 || u[i] - radius > cam.width - 1 || v[i] - radius > cam.height - 1) {
                continue
            }
            conic[3 * i] = c / det
            conic[3 * i + 1] = -b / det
            conic[3 * i + 2] = a / det
            depth[i] = t[2]
            opacity[i] = 1 / (1 + exp(-p.opacityLogits[i]))
            for (ch in 0 until 3) color[3 * i + ch] = max(0.0, 0.5 + c0 * p.shDc[3 * i + ch])
            visible += i
        }
        val order = visible.sortedWith(compareBy<Int>({ depth[it] }, { it }))
        val image = DoubleArray(3 * cam.width * cam.height)
        for (y in 0 until cam.height) for (x in 0 until cam.width) {
            var tr = 1.0
            val px = 3 * (y * cam.width + x)
            for (s in order) {
                val dx = u[s] - x
                val dy = v[s] - y
                val power = -0.5 * (conic[3 * s] * dx * dx + conic[3 * s + 2] * dy * dy) - conic[3 * s + 1] * dx * dy
                if (power > 0) continue
                val alpha = min(0.99, opacity[s] * exp(power))
                if (alpha < 1.0 / 255) continue
                val next = tr * (1 - alpha)
                if (next < 1e-4) break
                for (ch in 0 until 3) image[px + ch] += color[3 * s + ch] * alpha * tr
                tr = next
            }
        }
        return image
    }

    private fun loss(image: DoubleArray, wts: FloatArray) = image.indices.sumOf { image[it] * wts[it] }

    private fun analytic(
        p: Params, wts: FloatArray, threads: Int = 1, cam: CameraIntrinsics = k, lens: Lens? = null,
    ): SplatGrads {
        val cloud = p.cloud()
        val out = RenderBuffers(cam.width, cam.height)
        val grads = SplatGrads()
        Rasterizer(threads).use {
            it.forward(cloud, cam, pose, out, lens)
            it.backward(cloud, cam, pose, out, wts, grads, lens)
        }
        return grads
    }

    private fun array(grads: SplatGrads, group: String): FloatArray = when (group) {
        "positions" -> grads.positions
        "logScales" -> grads.logScales
        "rotations" -> grads.rotations
        "opacityLogits" -> grads.opacityLogits
        "shDc" -> grads.shDc
        else -> error(group)
    }

    /** Hardening: fails naming the first non-finite gradient, e.g. `rotations[6] (splat 1) is NaN`. */
    private fun assertFinite(grads: SplatGrads) {
        val groups = listOf("positions" to 3, "logScales" to 3, "rotations" to 4, "opacityLogits" to 1, "shDc" to 3) +
            listOf("viewGradNorms" to 1)
        for ((name, per) in groups) {
            val values = if (name == "viewGradNorms") grads.viewGradNorms else array(grads, name)
            for (j in 0 until per * grads.count) {
                val g = values[j]
                if (!g.isFinite()) throw AssertionError("$name[$j] (splat ${j / per}) is $g")
            }
        }
    }

    /** Every parameter of [splats]: analytic vs central difference; returns one line per disagreement. */
    private fun mismatches(
        p: Params, grads: SplatGrads, wts: FloatArray, splats: IntRange = 0 until p.count, lens: DoubleArray = pinhole,
    ): List<String> {
        val bad = mutableListOf<String>()
        for ((name, values) in p.groups) {
            val per = values.size / p.count
            for (j in splats.first * per until (splats.last + 1) * per) {
                val keep = values[j]
                values[j] = keep + h
                val plus = loss(render(p, lens = lens), wts)
                values[j] = keep - h
                val minus = loss(render(p, lens = lens), wts)
                values[j] = keep
                val numeric = (plus - minus) / (2 * h)
                val a = array(grads, name)[j].toDouble()
                val scale = max(abs(a), abs(numeric))
                val ok = if (scale < 1e-5) abs(a - numeric) < 1e-6 else abs(a - numeric) < 1e-3 * scale
                if (!ok) bad += "$name[$j] (splat ${j / per}): analytic $a, numeric $numeric"
            }
        }
        return bad
    }

    @Test
    fun `the Double reference renders what the Float rasteriser renders`() {
        for (p in listOf(scene, walled)) {
            val out = RenderBuffers(k.width, k.height)
            Rasterizer(1).use { it.forward(p.cloud(), k, pose, out) }
            val ref = render(p)
            var maxErr = 0.0
            for (j in ref.indices) maxErr = max(maxErr, abs(ref[j] - out.color[j]))
            assertTrue(maxErr < 1e-5, "max |reference - forward| = $maxErr")
            assertTrue(ref.max() > 0.1, "the scene must be visible")
        }
    }

    @Test
    fun `analytic gradients match central differences for every parameter of every splat`() {
        val wts = weights(3 * k.width * k.height)
        val grads = analytic(scene, wts)
        assertFinite(grads)
        val bad = mismatches(scene, grads, wts)
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        for ((name, values) in scene.groups) {
            for (i in 0 until scene.count) {
                val per = values.size / scene.count
                val largest = (0 until per).maxOf { abs(array(grads, name)[i * per + it]) }
                assertTrue(largest > 1e-3, "$name of splat $i has only ~0 gradients ($largest): the check would be empty")
            }
        }
    }

    @Test
    fun `the view gradient norm is the NDC length of dL by the projected centre`() {
        val wts = weights(3 * k.width * k.height, seed = 11)
        val grads = analytic(scene, wts)
        for (i in 0 until scene.count) {
            val g = DoubleArray(2) { axis ->
                val off = DoubleArray(2 * scene.count)
                off[2 * i + axis] = h
                val plus = loss(render(scene, offsets = off), wts)
                off[2 * i + axis] = -h
                val minus = loss(render(scene, offsets = off), wts)
                (plus - minus) / (2 * h)
            }
            val expected = sqrt((g[0] * k.width / 2).let { it * it } + (g[1] * k.height / 2).let { it * it })
            assertEquals(expected, grads.viewGradNorms[i].toDouble(), 1e-3 * expected, "splat $i")
        }
    }

    @Test
    fun `a splat hidden behind an opaque wall has zero gradient`() {
        val wts = weights(3 * k.width * k.height, seed = 3)
        val grads = analytic(walled, wts)
        assertFinite(grads)
        val hidden = walled.count - 1
        for ((name, values) in walled.groups) {
            val per = values.size / walled.count
            for (j in hidden * per until (hidden + 1) * per) {
                assertTrue(abs(array(grads, name)[j]) < 1e-6, "$name[$j] of the hidden splat = ${array(grads, name)[j]}")
            }
        }
        assertEquals(0f, grads.viewGradNorms[hidden])
        val bad = mismatches(walled, grads, wts, hidden..hidden)
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        // The wall itself is seen: its front layer has real gradients.
        assertTrue((0 until 3).maxOf { abs(grads.shDc[it]) } > 1e-2)
    }

    @Test
    fun `gradients are bit-identical for any thread count`() {
        val cam = CameraIntrinsics(fx = 40f, fy = 40f, cx = 23.5f, cy = 19.5f, width = 48, height = 40)
        val r = Random(5)
        fun u(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
        val many = params(
            List(60) {
                S(
                    d(u(-0.6, 0.6), u(-0.5, 0.5), u(1.5, 3.0)), d(u(0.03, 0.15), u(0.03, 0.15), u(0.03, 0.15)),
                    d(u(-1.0, 1.0), u(-1.0, 1.0), u(-1.0, 1.0), u(-1.0, 1.0)), u(0.2, 0.95), d(u(0.0, 1.0), u(0.0, 1.0), u(0.0, 1.0)),
                )
            },
        )
        val wts = weights(3 * cam.width * cam.height, seed = 9)
        val one = analytic(many, wts, threads = 1, cam = cam)
        assertFinite(one)
        for (threads in listOf(2, 5)) {
            val more = analytic(many, wts, threads = threads, cam = cam)
            for (name in listOf("positions", "logScales", "rotations", "opacityLogits", "shDc")) {
                assertArrayEquals(array(one, name), array(more, name), "$name, $threads threads")
            }
            assertArrayEquals(one.viewGradNorms, more.viewGradNorms, "viewGradNorms, $threads threads")
        }
        assertTrue(one.positions.any { abs(it) > 1e-3f }, "the scene must have gradients")
    }

    @Test
    fun `the pinhole lens renders bit-identically to no lens and leaves the lens gradients 0 without one`() {
        val cloud = scene.cloud()
        val plain = RenderBuffers(k.width, k.height)
        val unit = RenderBuffers(k.width, k.height)
        Rasterizer(1).use {
            it.forward(cloud, k, pose, plain)
            it.forward(cloud, k, pose, unit, Lens.PINHOLE)
        }
        assertArrayEquals(plain.color, unit.color)
        assertArrayEquals(plain.means2d, unit.means2d)
        assertArrayEquals(plain.conics, unit.conics)
        val grads = analytic(scene, weights(3 * k.width * k.height))
        assertEquals(0.0, grads.focalScale)
        assertEquals(0.0, grads.k1)
    }

    @Test
    fun `with a lens the Double reference renders what the Float rasteriser renders`() {
        val out = RenderBuffers(k.width, k.height)
        Rasterizer(1).use { it.forward(scene.cloud(), k, pose, out, lens) }
        val ref = render(scene, lens = lensD)
        var maxErr = 0.0
        for (j in ref.indices) maxErr = max(maxErr, abs(ref[j] - out.color[j]))
        assertTrue(maxErr < 1e-5, "max |reference - forward| = $maxErr")
        var moved = 0.0
        val plain = render(scene)
        for (j in ref.indices) moved = max(moved, abs(ref[j] - plain[j]))
        assertTrue(moved > 0.02, "the lens must change the image, max change $moved")
    }

    @Test
    fun `with a lens analytic gradients match central differences, focal scale and k1 included`() {
        val wts = weights(3 * k.width * k.height, seed = 13)
        val grads = analytic(scene, wts, lens = lens)
        assertFinite(grads)
        val bad = mismatches(scene, grads, wts, lens = lensD).toMutableList()
        for ((j, name) in listOf(0 to "focalScale", 1 to "k1")) {
            val plus = lensD.copyOf().also { it[j] += h }
            val minus = lensD.copyOf().also { it[j] -= h }
            val numeric = (loss(render(scene, lens = plus), wts) - loss(render(scene, lens = minus), wts)) / (2 * h)
            val a = if (j == 0) grads.focalScale else grads.k1
            assertTrue(abs(numeric) > 1e-2, "$name has only a ~0 gradient ($numeric): the check would be empty")
            if (abs(a - numeric) >= 1e-3 * max(abs(a), abs(numeric))) bad += "$name: analytic $a, numeric $numeric"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `lens gradients are bit-identical for any thread count`() {
        val wts = weights(3 * k.width * k.height, seed = 17)
        val one = analytic(scene, wts, threads = 1, lens = lens)
        for (threads in listOf(2, 5)) {
            val more = analytic(scene, wts, threads = threads, lens = lens)
            assertEquals(one.focalScale, more.focalScale, "$threads threads")
            assertEquals(one.k1, more.k1, "$threads threads")
            assertArrayEquals(one.positions, more.positions, "positions, $threads threads")
        }
    }

    @Test
    fun `a NaN gradient fails the check with the parameter's name`() {
        val grads = analytic(scene, weights(3 * k.width * k.height))
        assertFinite(grads)
        grads.rotations[6] = Float.NaN
        val e = assertThrows(AssertionError::class.java) { assertFinite(grads) }
        assertTrue(e.message!!.startsWith("rotations[6] (splat 1) is NaN"), e.message)
        grads.rotations[6] = 0f
        grads.positions[13] = Float.POSITIVE_INFINITY
        val inf = assertThrows(AssertionError::class.java) { assertFinite(grads) }
        assertTrue(inf.message!!.startsWith("positions[13] (splat 4)"), inf.message)
    }

    @Test
    fun `backward rejects buffers of another cloud and a wrong dLdColor size`() {
        val cloud = scene.cloud()
        val out = RenderBuffers(k.width, k.height)
        Rasterizer(1).use { r ->
            r.forward(cloud, k, pose, out)
            assertThrows(IllegalArgumentException::class.java) {
                r.backward(walled.cloud().let { c -> SplatCloud(4, 0, c.positions.copyOf(12), c.logScales.copyOf(12), c.rotations.copyOf(16), c.opacityLogits.copyOf(4), c.shDc.copyOf(12), FloatArray(0)) }, k, pose, out, FloatArray(3 * 256), SplatGrads())
            }
            assertThrows(IllegalArgumentException::class.java) {
                r.backward(cloud, k, pose, out, FloatArray(3), SplatGrads())
            }
        }
    }
}
