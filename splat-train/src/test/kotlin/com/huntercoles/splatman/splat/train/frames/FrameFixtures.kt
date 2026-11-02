package com.huntercoles.splatman.splat.train.frames

import com.huntercoles.splatman.splat.io.ply.PlyFormatException
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.tan

/**
 * Shared helpers of the frame suite (cards #143 and on): the real-scene fixtures in `src/test/resources/frames/`
 * (written by `python3 -I docs/samples/make_frame_fixtures.py`), loaded through the app's [PlyReader] into W, a
 * canonical camera that frames a cloud, CPU renders through [Rasterizer.forward], PSNR and per-half image mass.
 */
object FrameFixtures {

    /** One fixture file of [count] splats. */
    data class Fixture(val resource: String, val count: Int)

    /** The cactus in its pot, upright (SH degree 1): the up fixture. */
    val CACTUS_UP = Fixture("frames/cactus-up.ply", count = 11_618)

    /** The measuring tape (SH degree 0): chiral, so the handedness fixture. */
    val TAPE_HAND = Fixture("frames/tape-hand.ply", count = 9_735)

    /** Share of a fixture that makes up a known part. */
    const val KNOWN_SHARE = 0.2

    private const val REGENERATE = "regenerate with: python3 -I docs/samples/make_frame_fixtures.py"

    /** A camera: intrinsics and `T_W_Cgl`. */
    data class View(val intrinsics: CameraIntrinsics, val pose: CameraPose)

    /** Summed render alpha in each image half. */
    data class HalfMass(val left: Double, val right: Double, val top: Double, val bottom: Double) {
        val total: Double get() = left + right
    }

    /**
     * [fixture] read through [PlyReader] (file frame F -> world W). Missing, unreadable or short fixtures fail with
     * the fixture's name and how to regenerate it, never as a cloud of zero splats. [open] is for tests of this.
     */
    fun load(fixture: Fixture, open: () -> InputStream? = { resource(fixture) }): SplatCloud {
        val cloud = open().use { input ->
            if (input == null) throw AssertionError("fixture ${fixture.resource} is missing; $REGENERATE")
            try {
                PlyReader.read(input)
            } catch (e: PlyFormatException) {
                throw AssertionError("fixture ${fixture.resource} is unreadable (${e.message}); $REGENERATE", e)
            }
        }
        if (cloud.count != fixture.count) {
            throw AssertionError(
                "fixture ${fixture.resource} has ${cloud.count} splats, expected ${fixture.count}; $REGENERATE",
            )
        }
        return cloud
    }

    /**
     * [fixture] parsed as stored, in file frame F, without [PlyReader] or any frame conversion: the independent
     * reference for the reader's W. Only the little-endian float32 layout the script writes.
     */
    fun loadFileFrame(fixture: Fixture): SplatCloud {
        val bytes = resource(fixture)?.use { it.readBytes() }
            ?: throw AssertionError("fixture ${fixture.resource} is missing; $REGENERATE")
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        val end = (0..bytes.size - marker.size).first { at -> marker.indices.all { bytes[at + it] == marker[it] } } +
            marker.size
        val lines = String(bytes, 0, end, Charsets.US_ASCII).lines()
        val props = lines.filter { it.startsWith("property float ") }.map { it.removePrefix("property float ") }
        val n = lines.first { it.startsWith("element vertex ") }.removePrefix("element vertex ").toInt()
        check(n == fixture.count) { "fixture ${fixture.resource} has $n splats, expected ${fixture.count}" }
        check(bytes.size - end == n * props.size * 4) { "fixture ${fixture.resource} body is short; $REGENERATE" }
        val bb = ByteBuffer.wrap(bytes, end, bytes.size - end).order(ByteOrder.LITTLE_ENDIAN)
        val col = props.withIndex().associate { it.value to it.index }
        fun at(i: Int, p: String): Float = bb.getFloat(end + (i * props.size + col.getValue(p)) * 4)
        val rest = props.count { it.startsWith("f_rest_") }
        return SplatCloud(
            count = n,
            shDegree = when (rest) { 0 -> 0; 9 -> 1; 24 -> 2; else -> 3 },
            positions = FloatArray(3 * n) { at(it / 3, "xyz"[it % 3].toString()) },
            logScales = FloatArray(3 * n) { at(it / 3, "scale_${it % 3}") },
            rotations = FloatArray(4 * n) { at(it / 4, "rot_${it % 4}") },
            opacityLogits = FloatArray(n) { at(it, "opacity") },
            shDc = FloatArray(3 * n) { at(it / 3, "f_dc_${it % 3}") },
            // PLY f_rest is planar per channel (c * K + k), the SplatCloud layout too.
            shRest = FloatArray(n * rest) { at(it / rest, "f_rest_${it % rest}") },
        )
    }

    /**
     * The canonical camera for [cloud]: `T_W_Cgl` rotation identity (looking down W -Z, image up = W +Y, image
     * right = W +X), centred on the cloud's 2nd-98th percentile box, back far enough that the box fits with a
     * margin. Square image of [size] px, vertical field of view [fovYDeg], principal point at the image centre,
     * so a flip of W x about the box centre is exactly a left-right flip of the image.
     */
    fun canonicalView(cloud: SplatCloud, size: Int = 128, fovYDeg: Double = 50.0): View {
        val lo = FloatArray(3)
        val hi = FloatArray(3)
        for (a in 0 until 3) {
            val v = FloatArray(cloud.count) { cloud.positions[3 * it + a] }.also { it.sort() }
            lo[a] = v[(v.size * 0.02).toInt()]
            hi[a] = v[(v.size * 0.98).toInt()]
        }
        val centre = Vec3((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2)
        val halfFov = Math.toRadians(fovYDeg / 2)
        val halfSpan = max(hi[0] - lo[0], hi[1] - lo[1]) / 2 * FIT_MARGIN
        val distance = (halfSpan / tan(halfFov)).toFloat() + (hi[2] - lo[2]) / 2
        val focal = (size / 2.0 / tan(halfFov)).toFloat()
        val principal = (size - 1) / 2f
        return View(
            CameraIntrinsics(focal, focal, principal, principal, size, size),
            CameraPose(Quat.IDENTITY, Vec3(centre.x, centre.y, centre.z + distance)),
        )
    }

    /** The W x of the canonical camera's optical axis: mirror about it to flip the image left-right. */
    fun axisX(view: View): Float = view.pose.translationM.x

    /** [cloud] rendered on one thread from [view]. */
    fun render(cloud: SplatCloud, view: View): RenderBuffers {
        val out = RenderBuffers(view.intrinsics.width, view.intrinsics.height)
        Rasterizer(threads = 1).use { it.forward(cloud, view.intrinsics, view.pose, out) }
        return out
    }

    /** PSNR in dB of two colour images in 0..1 (infinite when equal). */
    fun psnr(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "images differ in size: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i].coerceIn(0f, 1f) - b[i].coerceIn(0f, 1f).toDouble()
            sum += d * d
        }
        val mse = sum / a.size
        return if (mse == 0.0) Double.POSITIVE_INFINITY else -10.0 * log10(mse)
    }

    /** The colour image of [buffers] flipped left-right, as a new array. */
    fun flipX(buffers: RenderBuffers): FloatArray {
        val w = buffers.width
        val out = FloatArray(buffers.color.size)
        for (y in 0 until buffers.height) for (x in 0 until w) for (c in 0 until 3) {
            out[(y * w + x) * 3 + c] = buffers.color[(y * w + (w - 1 - x)) * 3 + c]
        }
        return out
    }

    /**
     * [cloud] mirrored in the plane `x = [aboutX]` (W): positions `x -> 2 aboutX - x`, rotations `M R M` with
     * `M = diag(-1, 1, 1)`, i.e. quaternion `(w, x, -y, -z)`. SH degree 0 only (the rasteriser's colour).
     */
    fun mirrorX(cloud: SplatCloud, aboutX: Float): SplatCloud {
        require(cloud.shDegree == 0) { "mirrorX handles SH degree 0 only, got ${cloud.shDegree}" }
        val pos = cloud.positions.copyOf()
        for (i in 0 until cloud.count) pos[3 * i] = 2 * aboutX - pos[3 * i]
        val rot = cloud.rotations.copyOf()
        for (i in 0 until cloud.count) {
            rot[4 * i + 2] = -rot[4 * i + 2]
            rot[4 * i + 3] = -rot[4 * i + 3]
        }
        return SplatCloud(
            cloud.count, 0, pos, cloud.logScales.copyOf(), rot, cloud.opacityLogits.copyOf(), cloud.shDc.copyOf(),
            FloatArray(0),
        )
    }

    /**
     * The known-down part of a fixture: indices of the [KNOWN_SHARE] of [file] (raw file frame, [loadFileFrame])
     * with the highest file y. File y points down (ADR 0003), so these are physically the lowest; ties by index.
     */
    fun knownDown(file: SplatCloud): IntArray = extreme(file, axis = 1, sign = -1f)

    /** The known-left part: the [KNOWN_SHARE] of [file] with the lowest file x (file x is world x, ADR 0003). */
    fun knownLeft(file: SplatCloud): IntArray = extreme(file, axis = 0, sign = 1f)

    private fun extreme(file: SplatCloud, axis: Int, sign: Float): IntArray =
        (0 until file.count).sortedWith(compareBy<Int> { sign * file.positions[3 * it + axis] }.thenBy { it })
            .take((file.count * KNOWN_SHARE).toInt()).sorted().toIntArray()

    /** The splats [keep] of [cloud] (SH degree 0 kept only), as a new cloud. */
    fun pick(cloud: SplatCloud, keep: IntArray): SplatCloud {
        val n = keep.size
        return SplatCloud(
            n, 0,
            FloatArray(3 * n) { cloud.positions[3 * keep[it / 3] + it % 3] },
            FloatArray(3 * n) { cloud.logScales[3 * keep[it / 3] + it % 3] },
            FloatArray(4 * n) { cloud.rotations[4 * keep[it / 4] + it % 4] },
            FloatArray(n) { cloud.opacityLogits[keep[it]] },
            FloatArray(3 * n) { cloud.shDc[3 * keep[it / 3] + it % 3] },
            FloatArray(0),
        )
    }

    /** Image-half mass of splats [keep] of [cloud] seen from [view]: that subset rendered alone, alpha summed. */
    fun halfMass(cloud: SplatCloud, keep: IntArray, view: View): HalfMass {
        val out = render(pick(cloud, keep), view)
        val w = out.width
        val h = out.height
        var left = 0.0
        var right = 0.0
        var top = 0.0
        var bottom = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val a = out.alpha[y * w + x].toDouble()
            if (2 * x < w) left += a else right += a
            if (2 * y < h) top += a else bottom += a
        }
        return HalfMass(left, right, top, bottom)
    }

    /** Truncated bytes of [fixture], for the hardening tests. */
    fun truncated(fixture: Fixture, keep: Int): ByteArray =
        checkNotNull(resource(fixture)) { "fixture ${fixture.resource} is missing; $REGENERATE" }
            .use { it.readNBytes(keep) }

    private fun resource(fixture: Fixture): InputStream? =
        FrameFixtures::class.java.classLoader.getResourceAsStream(fixture.resource)

    private const val FIT_MARGIN = 1.15f
}
