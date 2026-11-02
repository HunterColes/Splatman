package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import com.huntercoles.splatman.splat.train.raster.SplatGrads
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The stage-2 loop of ADR 0005, one [step] per iteration: a keyframe drawn at random (seeded), the forward pass,
 * the L1 loss against the keyframe's image (mean over pixels and channels, 0..1 colour), the backward pass, Adam per
 * parameter group, then densification on the [OptimSettings] schedule with the [maxSplats] cap. With
 * [OptimSettings.refineCamera], after [OptimSettings.cameraFreezeSteps] iterations the [lens] (one focal scale on
 * every keyframe's fx, fy and one k1) is rendered with and learned too, by Adam, clamped to the settings' limits.
 *
 * The working cloud's arrays are written in place; it is never published, [snapshot] hands out copies.
 * Same inputs and seed give the same result for any [rasterizer] thread count. Not thread-safe.
 *
 * Throws [TrainingFailedException] when the cloud is or becomes empty, or a parameter (lens included) is not finite.
 */
@Suppress("LongParameterList")
class SplatOptimizer(
    initial: SplatCloud,
    private val keyframes: List<Keyframe>,
    /** Iterations planned; the position learning rate decays over them and densification stops at 60% of them. */
    val totalIterations: Int,
    /** Hard cap on the splat count while densifying. */
    private val maxSplats: Int,
    private val rasterizer: Rasterizer,
    private val settings: OptimSettings = OptimSettings(),
    seed: Long = 0L,
) {
    init {
        require(keyframes.isNotEmpty()) { "no keyframes to train on" }
        require(totalIterations > 0) { "totalIterations must be > 0, got $totalIterations" }
        require(maxSplats > 0) { "maxSplats must be > 0, got $maxSplats" }
    }

    private val random = Random(seed)

    /** The working model; its arrays change with every [step]. */
    var cloud: SplatCloud = copyOf(initial)
        private set

    /** Iterations done. */
    var iteration: Int = 0
        private set

    /** L1 loss of the last [step], NaN before the first. */
    var loss: Float = Float.NaN
        private set

    /** The learned lens; the pinhole until refinement starts, and always with [OptimSettings.refineCamera] off. */
    var lens: Lens = Lens.PINHOLE
        private set

    /** Scene extent, metres: 1.1 x the largest camera distance from the cameras' mean centre (3DGS). */
    val extent: Double = sceneExtent(keyframes, initial)

    private val targets: List<FloatArray> = keyframes.map { kf ->
        val bytes = kf.image.rgba
        FloatArray(3 * kf.image.width * kf.image.height) { j -> (bytes[4 * (j / 3) + j % 3].toInt() and 0xFF) / 255f }
    }
    private val buffers = HashMap<Long, RenderBuffers>()
    private val grads = SplatGrads()
    private var dLdColor = FloatArray(0)

    private val adamPositions = Adam(3)
    private val adamLogScales = Adam(3)
    private val adamRotations = Adam(4)
    private val adamOpacity = Adam(1)
    private val adamShDc = Adam(3)
    private val adamFocal = Adam(1)
    private val adamK1 = Adam(1)
    private val lensValue = FloatArray(1)
    private val lensGrad = FloatArray(1)

    private var gradSum = FloatArray(cloud.count)
    private var gradViews = IntArray(cloud.count)

    init {
        if (cloud.count == 0) throw TrainingFailedException(0, "the cloud is empty")
        checkFinite()
    }

    /** One iteration; returns its loss. */
    fun step(): Float {
        check(iteration < totalIterations) { "all $totalIterations iterations are done" }
        val t = iteration + 1
        val pick = random.nextInt(keyframes.size)
        val kf = keyframes[pick]
        val target = targets[pick]
        val out = buffers.getOrPut(kf.image.width.toLong() shl 32 or kf.image.height.toLong()) {
            RenderBuffers(kf.image.width, kf.image.height)
        }
        val refine = settings.refinesCameraAt(t)
        val stepLens = if (refine) lens else null
        rasterizer.forward(cloud, kf.intrinsics, kf.pose, out, stepLens)
        loss = l1(out.color, target)
        rasterizer.backward(cloud, kf.intrinsics, kf.pose, out, dLdColor, grads, stepLens)

        val n = cloud.count
        for (i in 0 until n) {
            if (out.radii[i] > 0) {
                gradSum[i] += grads.viewGradNorms[i]
                gradViews[i]++
            }
        }
        adamPositions.step(cloud.positions, grads.positions, n, positionLr(t - 1) * extent)
        adamLogScales.step(cloud.logScales, grads.logScales, n, settings.logScaleLr)
        adamRotations.step(cloud.rotations, grads.rotations, n, settings.rotationLr)
        adamOpacity.step(cloud.opacityLogits, grads.opacityLogits, n, settings.opacityLr)
        adamShDc.step(cloud.shDc, grads.shDc, n, settings.shDcLr)
        if (refine) stepLens(t)
        iteration = t
        checkFinite()
        if (settings.densifiesAt(t, totalIterations)) densify()
        return loss
    }

    private fun stepLens(t: Int) {
        val focal = adamOne(adamFocal, lens.focalScale, grads.focalScale, settings.focalScaleLr)
        val k1 = adamOne(adamK1, lens.k1, grads.k1, settings.k1Lr)
        if (!focal.isFinite() || !k1.isFinite()) {
            throw TrainingFailedException(t, "lens is not finite: focalScale $focal, k1 $k1")
        }
        val fLim = settings.focalScaleLimit
        lens = Lens(
            focal.coerceIn((1 - fLim).toFloat(), (1 + fLim).toFloat()),
            k1.coerceIn(-settings.k1Limit.toFloat(), settings.k1Limit.toFloat()),
        )
    }

    private fun adamOne(adam: Adam, value: Float, grad: Double, lr: Double): Float {
        lensValue[0] = value
        lensGrad[0] = grad.toFloat()
        adam.step(lensValue, lensGrad, 1, lr)
        return lensValue[0]
    }

    /** A copy of the working model, safe to publish. */
    fun snapshot(): SplatCloud = copyOf(cloud)

    /** The position learning rate (x extent) at 0-based step [s]: log-linear from initial to final over the run. */
    internal fun positionLr(s: Int): Double {
        val f = (s.toDouble() / totalIterations).coerceIn(0.0, 1.0)
        return exp(ln(settings.positionLr) * (1 - f) + ln(settings.positionLrFinal) * f)
    }

    private fun densify() {
        val n = cloud.count
        val mean = FloatArray(n) { i -> if (gradViews[i] > 0) gradSum[i] / gradViews[i] else 0f }
        val result = Densifier.densify(cloud, mean, extent, maxSplats, settings, random)
        cloud = result.cloud
        for (adam in listOf(adamPositions, adamLogScales, adamRotations, adamOpacity, adamShDc)) adam.remap(result.source)
        gradSum = FloatArray(cloud.count)
        gradViews = IntArray(cloud.count)
        if (cloud.count == 0) throw TrainingFailedException(iteration, "every splat was pruned")
    }

    /** Mean |rendered - target| over pixels and channels; fills [dLdColor] with its gradient. */
    private fun l1(rendered: FloatArray, target: FloatArray): Float {
        val size = target.size
        if (dLdColor.size < size) dLdColor = FloatArray(size)
        val scale = 1f / size
        var sum = 0.0
        for (j in 0 until size) {
            val d = rendered[j] - target[j]
            sum += abs(d)
            dLdColor[j] = when {
                d > 0f -> scale
                d < 0f -> -scale
                else -> 0f
            }
        }
        return (sum / size).toFloat()
    }

    private fun checkFinite() {
        val c = cloud
        val groups = listOf(
            "positions" to c.positions, "logScales" to c.logScales, "rotations" to c.rotations,
            "opacityLogits" to c.opacityLogits, "shDc" to c.shDc, "shRest" to c.shRest,
        )
        for ((name, values) in groups) {
            for (j in values.indices) {
                if (!values[j].isFinite()) {
                    throw TrainingFailedException(iteration, "$name[$j] is ${values[j]}")
                }
            }
        }
    }

    private companion object {
        /** 3DGS's factor on the camera radius. */
        const val EXTENT_MARGIN = 1.1

        /** Below this camera spread (one keyframe, or a tripod), the seed cloud's spread sets the extent. */
        const val MIN_EXTENT = 1e-3

        fun copyOf(c: SplatCloud) = SplatCloud(
            c.count, c.shDegree, c.positions.copyOf(), c.logScales.copyOf(), c.rotations.copyOf(),
            c.opacityLogits.copyOf(), c.shDc.copyOf(), c.shRest.copyOf(),
        )

        fun sceneExtent(keyframes: List<Keyframe>, cloud: SplatCloud): Double {
            val cams = keyframes.map { kf ->
                doubleArrayOf(
                    kf.pose.translationM.x.toDouble(), kf.pose.translationM.y.toDouble(),
                    kf.pose.translationM.z.toDouble(),
                )
            }
            val fromCams = radius(cams)
            if (fromCams >= MIN_EXTENT) return EXTENT_MARGIN * fromCams
            val pts = (0 until cloud.count).map { i ->
                doubleArrayOf(
                    cloud.positions[3 * i].toDouble(), cloud.positions[3 * i + 1].toDouble(),
                    cloud.positions[3 * i + 2].toDouble(),
                )
            }
            return EXTENT_MARGIN * maxOf(radius(pts), MIN_EXTENT)
        }

        /** Largest distance of [points] from their mean, 0 for none. */
        private fun radius(points: List<DoubleArray>): Double {
            if (points.isEmpty()) return 0.0
            val mean = DoubleArray(3) { a -> points.sumOf { it[a] } / points.size }
            val r = points.maxOf { p -> sqrt((0 until 3).sumOf { a -> (p[a] - mean[a]) * (p[a] - mean[a]) }) }
            return if (r.isFinite()) r else 0.0
        }
    }
}
