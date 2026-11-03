package com.huntercoles.splatman.splat.train.still

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A still of a splat for the scan's thumbnail (ticket #175): the cloud's robust bounds (the [OUTLIER_FRACTION]
 * farthest splats from the per-axis median ignored) seen from the front three-quarter view, [YAW_DEG] round world
 * up and [PITCH_DEG] above the horizon (ADR 0003: W is +Y up and the camera Cgl looks down -Z, so the eye sits on
 * the +Z side the capture started from), drawn with the CPU [Rasterizer.forward].
 *
 * Splats with a non-finite parameter are skipped; a cloud over [MAX_SPLATS] keeps only its most opaque [MAX_SPLATS].
 */
object SplatStill {
    const val DEFAULT_WIDTH = 256
    const val DEFAULT_HEIGHT = 320

    /** At most this many splats are drawn, the most opaque. */
    const val MAX_SPLATS = 200_000

    /** The farthest fraction of splats left out of the framing. */
    const val OUTLIER_FRACTION = 0.01f

    /** Front three-quarter view: yaw round world +Y and elevation, degrees. */
    const val YAW_DEG = 30f
    const val PITCH_DEG = 20f

    /** Horizontal field of view, degrees. */
    const val FOV_X_DEG = 50f

    /** The bounds' sphere fills about this fraction of the image width. */
    const val FILL = 0.7f

    /** Smallest framed radius, metres: keeps a tiny cloud beyond the rasteriser's near plane. */
    const val MIN_RADIUS_M = 0.1f

    private const val OPAQUE = -0x1000000

    /**
     * ARGB pixels, row-major `y * width + x`, opaque: the splat composited over [background] (an ARGB colour, its
     * alpha ignored). An empty cloud (or one with no finite splat) is all [background].
     */
    fun render(cloud: SplatCloud, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, background: Int): IntArray {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        val opaqueBackground = background or OPAQUE
        val drawn = prepare(cloud)
        if (drawn.count == 0) return IntArray(width * height) { opaqueBackground }
        val intrinsics = intrinsics(width, height)
        val buffers = RenderBuffers(width, height)
        Rasterizer().use { it.forward(drawn, intrinsics, framing(drawn, intrinsics), buffers) }
        val bgR = (background shr 16 and 0xFF) / 255f
        val bgG = (background shr 8 and 0xFF) / 255f
        val bgB = (background and 0xFF) / 255f
        return IntArray(width * height) { p ->
            val t = buffers.finalT[p]
            val r = channel(buffers.color[3 * p] + t * bgR)
            val g = channel(buffers.color[3 * p + 1] + t * bgG)
            val b = channel(buffers.color[3 * p + 2] + t * bgB)
            OPAQUE or (r shl 16) or (g shl 8) or b
        }
    }

    /** The splats drawn: finite ones only, at most [MAX_SPLATS] (the most opaque), SH degree 0. */
    internal fun prepare(cloud: SplatCloud): SplatCloud {
        var kept = IntArray(cloud.count)
        var n = 0
        for (i in 0 until cloud.count) if (isFinite(cloud, i)) kept[n++] = i
        if (n > MAX_SPLATS) {
            kept = mostOpaque(cloud, kept, n)
            n = MAX_SPLATS
        }
        return subset(cloud, kept, n)
    }

    /** Pose `T_W_Cgl` looking at the robust bounds' centre from the front three-quarter view; [cloud] non-empty. */
    internal fun framing(cloud: SplatCloud, intrinsics: CameraIntrinsics): CameraPose {
        val (centre, radius) = robustBounds(cloud)
        val tanHalf = (intrinsics.width / 2f) / intrinsics.fx
        val distance = radius / (FILL * tanHalf)
        val rotation = orientation()
        return CameraPose(rotation, centre + rotation.rotate(Vec3.UNIT_Z) * distance)
    }

    /** Yaw [YAW_DEG] about world up after tilting the eye [PITCH_DEG] above the horizon (camera Cgl -> W). */
    internal fun orientation(): Quat {
        val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, Math.toRadians(YAW_DEG.toDouble()).toFloat())
        val pitch = Quat.fromAxisAngle(Vec3.UNIT_X, -Math.toRadians(PITCH_DEG.toDouble()).toFloat())
        return (yaw * pitch).normalized()
    }

    internal fun intrinsics(width: Int, height: Int): CameraIntrinsics {
        val f = (width / 2f) / tan(Math.toRadians(FOV_X_DEG / 2.0)).toFloat()
        return CameraIntrinsics(f, f, (width - 1) / 2f, (height - 1) / 2f, width, height)
    }

    /** Centre (per-axis median) and the radius holding all but the [OUTLIER_FRACTION] farthest splats. */
    internal fun robustBounds(cloud: SplatCloud): Pair<Vec3, Float> {
        val n = cloud.count
        val p = cloud.positions
        val centre = Vec3(median(p, 0, n), median(p, 1, n), median(p, 2, n))
        val dist = FloatArray(n) { i ->
            val dx = p[3 * i] - centre.x
            val dy = p[3 * i + 1] - centre.y
            val dz = p[3 * i + 2] - centre.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        dist.sort()
        val at = ((1f - OUTLIER_FRACTION) * (n - 1)).roundToInt().coerceIn(0, n - 1)
        return centre to max(dist[at], MIN_RADIUS_M)
    }

    private fun median(positions: FloatArray, axis: Int, n: Int): Float {
        val values = FloatArray(n) { positions[3 * it + axis] }
        values.sort()
        return values[n / 2]
    }

    /** The [MAX_SPLATS] of `kept[0 until n]` with the highest opacity logits, ties by index, in index order. */
    private fun mostOpaque(cloud: SplatCloud, kept: IntArray, n: Int): IntArray {
        val logits = FloatArray(n) { cloud.opacityLogits[kept[it]] }
        logits.sort()
        val threshold = logits[n - MAX_SPLATS]
        var above = 0
        for (k in 0 until n) if (cloud.opacityLogits[kept[k]] > threshold) above++
        var ties = MAX_SPLATS - above
        val out = IntArray(MAX_SPLATS)
        var m = 0
        for (k in 0 until n) {
            val logit = cloud.opacityLogits[kept[k]]
            val take = logit > threshold || (logit == threshold && ties > 0)
            if (take) {
                if (logit == threshold) ties--
                out[m++] = kept[k]
            }
        }
        return out
    }

    private fun isFinite(cloud: SplatCloud, i: Int): Boolean =
        finiteRange(cloud.positions, 3 * i, 3) && finiteRange(cloud.logScales, 3 * i, 3) &&
            finiteRange(cloud.rotations, 4 * i, 4) && cloud.opacityLogits[i].isFinite() &&
            finiteRange(cloud.shDc, 3 * i, 3)

    private fun finiteRange(values: FloatArray, from: Int, count: Int): Boolean {
        for (k in from until from + count) if (!values[k].isFinite()) return false
        return true
    }

    private fun subset(cloud: SplatCloud, kept: IntArray, n: Int): SplatCloud {
        val positions = FloatArray(3 * n)
        val logScales = FloatArray(3 * n)
        val rotations = FloatArray(4 * n)
        val opacities = FloatArray(n)
        val shDc = FloatArray(3 * n)
        for (k in 0 until n) {
            val i = kept[k]
            System.arraycopy(cloud.positions, 3 * i, positions, 3 * k, 3)
            System.arraycopy(cloud.logScales, 3 * i, logScales, 3 * k, 3)
            System.arraycopy(cloud.rotations, 4 * i, rotations, 4 * k, 4)
            opacities[k] = cloud.opacityLogits[i]
            System.arraycopy(cloud.shDc, 3 * i, shDc, 3 * k, 3)
        }
        return SplatCloud(n, 0, positions, logScales, rotations, opacities, shDc, FloatArray(0))
    }

    private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()
}
