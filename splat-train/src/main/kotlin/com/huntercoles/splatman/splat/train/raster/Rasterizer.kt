package com.huntercoles.splatman.splat.train.raster

import com.huntercoles.splatman.splat.frames.FrameConversions
import com.huntercoles.splatman.splat.math.Mat3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.projection.Covariance
import com.huntercoles.splatman.splat.sh.ShEval
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The CPU tile rasteriser of ADR 0005, forward and [backward] passes; also the golden renderer of the tests
 * (ADR 0009).
 *
 * [forward] projects every splat with [Covariance.project] (EWA, 0.3 px low-pass, 3-sigma radius), culls, bins the
 * visible ones into 16x16 px tiles, orders each tile's list front to back (by depth, ties by index) and composites
 * per pixel front to back (alpha capped at 0.99, alphas under 1/255 skipped, stop before the transmittance drops
 * under 1e-4), colour from SH degree 0 only (higher bands are ignored), black background, as in 3DGS / gsplat.
 *
 * Work is split over [threads] (the caller plus `threads - 1` daemon workers): splats in fixed chunks for the
 * projection, then tiles in a fixed order. Every splat and every pixel is computed by the same arithmetic whichever
 * thread runs it, so the output is bit-identical for any thread count. [close] stops the workers.
 */
class Rasterizer(val threads: Int = Runtime.getRuntime().availableProcessors()) : AutoCloseable {
    init {
        require(threads >= 1) { "threads must be >= 1, got $threads" }
    }

    private val pool: ExecutorService? = if (threads == 1) {
        null
    } else {
        val made = AtomicInteger()
        Executors.newFixedThreadPool(threads - 1) { r ->
            Thread(r, "raster-${made.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    /**
     * Renders [cloud] (world frame W) seen by a camera with [intrinsics] at [pose] (`T_W_Cgl`, ADR 0003) into [out],
     * whose size must match the intrinsics. Overwrites every field of [out]. A [lens] corrects the pinhole (see
     * [Lens]); null is the plain pinhole, and so is `Lens(1f, 0f)`, bit for bit.
     */
    fun forward(
        cloud: SplatCloud, intrinsics: CameraIntrinsics, pose: CameraPose, out: RenderBuffers, lens: Lens? = null,
    ) {
        require(out.width == intrinsics.width && out.height == intrinsics.height) {
            "buffers ${out.width}x${out.height} do not match intrinsics ${intrinsics.width}x${intrinsics.height}"
        }
        out.ensureSplats(cloud.count)
        project(cloud, intrinsics, pose, out, lens ?: Lens.PINHOLE)
        bin(cloud.count, out)
        parallelFor(out.tileCount) { t -> compositeTile(t, out) }
    }

    /**
     * Gradients of a loss with respect to every raw parameter of [cloud] into [grads], given [dLdColor] (3 floats per
     * pixel, dL / d [RenderBuffers.color], row-major like it). [forward] must hold this rasteriser's [forward] pass
     * of the same cloud, intrinsics and pose, unchanged since: its lists, final transmittance and contributor counts
     * are replayed back to front (see [RasterizerBackward]). Overwrites [grads]; bit-identical for any thread count.
     * [lens] must be the forward pass's: non-null also fills [SplatGrads.focalScale] and [SplatGrads.k1]; null leaves
     * them 0 and runs the plain pinhole with exactly the arithmetic it had before lenses existed.
     */
    @Suppress("LongParameterList")
    fun backward(
        cloud: SplatCloud, intrinsics: CameraIntrinsics, pose: CameraPose,
        forward: RenderBuffers, dLdColor: FloatArray, grads: SplatGrads, lens: Lens? = null,
    ) = RasterizerBackward.run(this, cloud, intrinsics, pose, forward, dLdColor, grads, lens)

    override fun close() {
        pool?.shutdownNow()
    }

    private fun project(cloud: SplatCloud, k: CameraIntrinsics, pose: CameraPose, out: RenderBuffers, lens: Lens) {
        val cvPose = FrameConversions.glToCv(pose)
        // R_W_Ccv; world -> Ccv is its transpose applied to (p - t).
        val rotWorldToCv = cvPose.rotation.normalized().toMat3().transpose()
        val r = rotWorldToCv.values // column-major: (row, col) at col * 3 + row
        val tx = cvPose.translationM.x
        val ty = cvPose.translationM.y
        val tz = cvPose.translationM.z
        val chunks = (cloud.count + PROJECT_CHUNK - 1) / PROJECT_CHUNK
        parallelFor(chunks) { chunk ->
            val meanCv = FloatArray(3)
            val cov3 = FloatArray(6)
            val cov2 = FloatArray(4)
            val end = min(cloud.count, (chunk + 1) * PROJECT_CHUNK)
            for (i in chunk * PROJECT_CHUNK until end) {
                val px = cloud.positions[3 * i] - tx
                val py = cloud.positions[3 * i + 1] - ty
                val pz = cloud.positions[3 * i + 2] - tz
                meanCv[0] = r[0] * px + r[3] * py + r[6] * pz
                meanCv[1] = r[1] * px + r[4] * py + r[7] * pz
                meanCv[2] = r[2] * px + r[5] * py + r[8] * pz
                out.radii[i] = 0
                out.depths[i] = meanCv[2]
                projectOne(cloud, i, k, lens, rotWorldToCv, meanCv, cov3, cov2, out)
            }
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun projectOne(
        cloud: SplatCloud, i: Int, k: CameraIntrinsics, lens: Lens, rotWorldToCv: Mat3,
        meanCv: FloatArray, cov3: FloatArray, cov2: FloatArray, out: RenderBuffers,
    ) {
        // Lens(1, 0): fx * 1 = fx, d = 1 + 0 * r2 = 1 and the products by d are exact, so the pinhole is unchanged.
        if (!(meanCv[2] > Covariance.NEAR_Z)) return
        val fx = k.fx * lens.focalScale
        val fy = k.fy * lens.focalScale
        val xn = meanCv[0] / meanCv[2]
        val yn = meanCv[1] / meanCv[2]
        val d = 1f + lens.k1 * (xn * xn + yn * yn)
        if (!(d > 0f)) return
        Covariance.world(cloud.logScales, 3 * i, cloud.rotations, 4 * i, cov3, 0)
        Covariance.project(meanCv, 0, cov3, 0, rotWorldToCv, fx * d, fy * d, cov2, 0) ?: return
        val a = cov2[0]
        val b = cov2[1]
        val c = cov2[2]
        val det = a * c - b * b
        if (!(det > 0f)) return
        val u = fx * meanCv[0] / meanCv[2] * d + k.cx
        val v = fy * meanCv[1] / meanCv[2] * d + k.cy
        val radius = ceil(cov2[3])
        if (!u.isFinite() || !v.isFinite() || !(radius > 0f)) return
        // Wholly off the image: no tile would hold it.
        if (u + radius < 0f || v + radius < 0f || u - radius > out.width - 1 || v - radius > out.height - 1) return
        out.radii[i] = if (radius >= Int.MAX_VALUE.toFloat()) Int.MAX_VALUE else radius.toInt()
        out.means2d[2 * i] = u
        out.means2d[2 * i + 1] = v
        out.conics[3 * i] = c / det
        out.conics[3 * i + 1] = -b / det
        out.conics[3 * i + 2] = a / det
        for (ch in 0 until 3) {
            out.colors[3 * i + ch] = max(0f, (0.5 + ShEval.C0 * cloud.shDc[3 * i + ch]).toFloat())
        }
        val opacity = 1f / (1f + exp(-cloud.opacityLogits[i]))
        out.opacities[i] = opacity
        // Below this power, opacity * exp(power) < ALPHA_MIN for sure (the margin covers float rounding), so the
        // compositor skips the exp; at or above it the exact test decides. Only a shortcut: results are unchanged.
        out.powerCuts[i] = (ln(ALPHA_MIN.toDouble() / opacity) - POWER_CUT_MARGIN).toFloat()
    }

    /** Tile rect of splat [i] as `x0, x1, y0, y1` (end exclusive) into [rect]; empty when x0 >= x1 or y0 >= y1. */
    private fun tileRect(i: Int, out: RenderBuffers, rect: IntArray) {
        val r = out.radii[i].toFloat()
        val u = out.means2d[2 * i]
        val v = out.means2d[2 * i + 1]
        rect[0] = clampTile(floor((u - r) / TILE), out.tilesX)
        rect[1] = clampTile(floor((u + r) / TILE) + 1, out.tilesX)
        rect[2] = clampTile(floor((v - r) / TILE), out.tilesY)
        rect[3] = clampTile(floor((v + r) / TILE) + 1, out.tilesY)
    }

    private fun clampTile(t: Float, tiles: Int): Int = t.coerceIn(0f, tiles.toFloat()).toInt()

    /** Sorts the visible splats front to back once, then bins them in that order: every tile list comes out sorted. */
    private fun bin(n: Int, out: RenderBuffers) {
        var visible = 0
        for (i in 0 until n) if (out.radii[i] > 0) visible++
        // Depth > NEAR_Z > 0, so its float bits order like the floats; the low half keeps ties in index order.
        val keys = LongArray(visible)
        var j = 0
        for (i in 0 until n) {
            if (out.radii[i] > 0) keys[j++] = (out.depths[i].toRawBits().toLong() shl 32) or i.toLong()
        }
        keys.sort()

        val start = out.tileStart
        start.fill(0)
        val rect = IntArray(4)
        var total = 0L
        for (key in keys) {
            tileRect(key.toInt(), out, rect)
            for (ty in rect[2] until rect[3]) for (tx in rect[0] until rect[1]) {
                start[ty * out.tilesX + tx + 1]++
                total++
            }
        }
        require(total <= Int.MAX_VALUE) { "too many tile entries: $total" }
        for (t in 1..out.tileCount) start[t] += start[t - 1]
        out.ensureTileEntries(total.toInt())
        val cursor = start.copyOf(out.tileCount)
        val list = out.tileSplats
        for (key in keys) {
            val i = key.toInt()
            tileRect(i, out, rect)
            for (ty in rect[2] until rect[3]) for (tx in rect[0] until rect[1]) {
                list[cursor[ty * out.tilesX + tx]++] = i
            }
        }
    }

    private fun compositeTile(t: Int, out: RenderBuffers) {
        val x0 = (t % out.tilesX) * TILE
        val y0 = (t / out.tilesX) * TILE
        val x1 = min(x0 + TILE, out.width)
        val y1 = min(y0 + TILE, out.height)
        val begin = out.tileStart[t]
        val end = out.tileStart[t + 1]
        val list = out.tileSplats
        val means = out.means2d
        val conics = out.conics
        val colors = out.colors
        val opacities = out.opacities
        val cuts = out.powerCuts
        for (y in y0 until y1) for (x in x0 until x1) {
            var tr = 1f
            var r = 0f
            var g = 0f
            var b = 0f
            var walked = 0
            var last = 0
            for (k in begin until end) {
                val s = list[k]
                walked++
                val dx = means[2 * s] - x
                val dy = means[2 * s + 1] - y
                val power = -0.5f * (conics[3 * s] * dx * dx + conics[3 * s + 2] * dy * dy) -
                    conics[3 * s + 1] * dx * dy
                if (power > 0f || power < cuts[s]) continue
                val alpha = min(ALPHA_MAX, opacities[s] * exp(power))
                if (alpha < ALPHA_MIN) continue
                val next = tr * (1f - alpha)
                if (next < T_MIN) break
                val w = alpha * tr
                r += colors[3 * s] * w
                g += colors[3 * s + 1] * w
                b += colors[3 * s + 2] * w
                tr = next
                last = walked
            }
            val p = y * out.width + x
            out.color[3 * p] = r
            out.color[3 * p + 1] = g
            out.color[3 * p + 2] = b
            out.finalT[p] = tr
            out.alpha[p] = 1f - tr
            out.contributors[p] = last
        }
    }

    /** Runs `body` for `0 until count` on the caller and the workers; rethrows the first failure once all are done. */
    internal fun parallelFor(count: Int, body: (Int) -> Unit) {
        val pool = pool
        if (pool == null || count <= 1) {
            for (i in 0 until count) body(i)
            return
        }
        val next = AtomicInteger()
        val worker = Runnable {
            while (true) {
                val i = next.getAndIncrement()
                if (i >= count) break
                body(i)
            }
        }
        val futures = List(min(threads - 1, count - 1)) { pool.submit(worker) }
        var error: Throwable? = null
        try {
            worker.run()
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            error = e
        }
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (error == null) error = e.cause ?: e
            }
        }
        error?.let { throw it }
    }

    companion object {
        /** Tile side in px. */
        const val TILE = 16

        /** Per-splat alpha cap. */
        const val ALPHA_MAX = 0.99f

        /** Alphas under this are skipped. */
        const val ALPHA_MIN = 1f / 255f

        /** A pixel stops before a splat would leave less transmittance than this. */
        const val T_MIN = 1e-4f

        private const val PROJECT_CHUNK = 1024

        private const val POWER_CUT_MARGIN = 1e-3
    }
}
