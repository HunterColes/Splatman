package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The stage-2 loop of ADR 0005, one [step] per iteration: a keyframe drawn at random (seeded), then the [backend]'s
 * iteration on it (forward, the L1 loss against the keyframe's image, mean over pixels and channels, 0..1 colour,
 * backward, Adam per parameter group), then densification on the [OptimSettings] schedule with the [maxSplats] cap.
 * With [OptimSettings.refineCamera] on a backend that [TrainBackend.learnsLens], after
 * [OptimSettings.cameraFreezeSteps] iterations the [lens] (one focal scale on every keyframe's fx, fy and one k1) is
 * rendered with and learned too, by Adam, clamped to the settings' limits.
 *
 * The optimiser keeps the schedule (keyframe draw, learning rates, densification, lens, finite checks); the backend
 * keeps the per-splat state. On a [CpuBackend] the cloud is checked in place after every step; on any other backend
 * the loss is checked every step and the full state at every read. The caller closes the backend.
 * Same inputs and seed give the same result for any [Rasterizer] thread count. Not thread-safe.
 *
 * Throws [TrainingFailedException] when the cloud is or becomes empty, or a parameter (lens included) or the loss is
 * not finite.
 */
@Suppress("LongParameterList")
class SplatOptimizer(
    initial: SplatCloud,
    keyframes: List<Keyframe>,
    /** Iterations planned; the position learning rate decays over them and densification stops at 60% of them. */
    val totalIterations: Int,
    /** Hard cap on the splat count while densifying. */
    private val maxSplats: Int,
    private val backend: TrainBackend,
    private val settings: OptimSettings = OptimSettings(),
    seed: Long = 0L,
) {
    /** The CPU path: a [CpuBackend] on [rasterizer], which stays the caller's. */
    constructor(
        initial: SplatCloud,
        keyframes: List<Keyframe>,
        totalIterations: Int,
        maxSplats: Int,
        rasterizer: Rasterizer,
        settings: OptimSettings = OptimSettings(),
        seed: Long = 0L,
    ) : this(initial, keyframes, totalIterations, maxSplats, CpuBackend(keyframes, rasterizer), settings, seed)

    init {
        require(keyframes.isNotEmpty()) { "no keyframes to train on" }
        require(totalIterations > 0) { "totalIterations must be > 0, got $totalIterations" }
        require(maxSplats > 0) { "maxSplats must be > 0, got $maxSplats" }
    }

    private val keyframeCount = keyframes.size
    private val random = Random(seed)
    private val cpu = backend as? CpuBackend

    /**
     * The working model. On a [CpuBackend] this is the live cloud, whose arrays change with every [step]; on any other
     * backend it is a checked copy read from it.
     */
    val cloud: SplatCloud
        get() = cpu?.cloud ?: readChecked().cloud

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

    private val adamFocal = Adam(1)
    private val adamK1 = Adam(1)
    private val lensValue = FloatArray(1)
    private val lensGrad = FloatArray(1)

    init {
        if (initial.count == 0) throw TrainingFailedException(0, "the cloud is empty")
        checkFinite(initial)
        backend.load(TrainState.fresh(copyOf(initial)))
    }

    /** One iteration; returns its loss. */
    fun step(): Float {
        check(iteration < totalIterations) { "all $totalIterations iterations are done" }
        val t = iteration + 1
        val pick = random.nextInt(keyframeCount)
        val refine = settings.refinesCameraAt(t) && backend.learnsLens
        backend.setLens(if (refine) lens else null)
        val rates = StepRates(
            positions = positionLr(t - 1) * extent,
            logScales = settings.logScaleLr,
            rotations = settings.rotationLr,
            opacity = settings.opacityLr,
            shDc = settings.shDcLr,
        )
        loss = backend.step(pick, rates)
        if (cpu == null && !loss.isFinite()) throw TrainingFailedException(t, "loss is $loss")
        if (refine) stepLens(t)
        iteration = t
        if (cpu != null) checkFinite(cpu.cloud)
        if (settings.densifiesAt(t, totalIterations)) densify()
        return loss
    }

    private fun stepLens(t: Int) {
        val g = backend.lensGrad()
        val focal = adamOne(adamFocal, lens.focalScale, g.focalScale, settings.focalScaleLr)
        val k1 = adamOne(adamK1, lens.k1, g.k1, settings.k1Lr)
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
    fun snapshot(): SplatCloud = if (cpu != null) copyOf(cpu.cloud) else readChecked().cloud

    /** The position learning rate (x extent) at 0-based step [s]: log-linear from initial to final over the run. */
    internal fun positionLr(s: Int): Double {
        val f = (s.toDouble() / totalIterations).coerceIn(0.0, 1.0)
        return exp(ln(settings.positionLr) * (1 - f) + ln(settings.positionLrFinal) * f)
    }

    /** Read, [Densifier], remap the moments, load. */
    private fun densify() {
        val state = if (cpu != null) backend.read() else readChecked()
        val n = state.cloud.count
        val mean = FloatArray(n) { i -> if (state.gradViews[i] > 0) state.gradSum[i] / state.gradViews[i] else 0f }
        val result = Densifier.densify(state.cloud, mean, extent, maxSplats, settings, random)
        val next = result.cloud
        if (next.count == 0) throw TrainingFailedException(iteration, "every splat was pruned")
        val widths = TrainState.GROUP_WIDTHS
        backend.load(
            TrainState(
                next,
                widths.indices.map { g -> Adam.remapMoments(state.adamM[g], widths[g], result.source) },
                widths.indices.map { g -> Adam.remapMoments(state.adamV[g], widths[g], result.source) },
                state.adamSteps,
                FloatArray(next.count),
                IntArray(next.count),
            ),
        )
    }

    /** [TrainBackend.read], with every float array of the state checked finite. */
    private fun readChecked(): TrainState {
        val state = backend.read()
        checkFinite(state.cloud)
        for (g in GROUP_NAMES.indices) {
            checkFinite("adamM.${GROUP_NAMES[g]}", state.adamM[g])
            checkFinite("adamV.${GROUP_NAMES[g]}", state.adamV[g])
        }
        checkFinite("gradSum", state.gradSum)
        return state
    }

    private fun checkFinite(c: SplatCloud) {
        checkFinite("positions", c.positions)
        checkFinite("logScales", c.logScales)
        checkFinite("rotations", c.rotations)
        checkFinite("opacityLogits", c.opacityLogits)
        checkFinite("shDc", c.shDc)
        checkFinite("shRest", c.shRest)
    }

    private fun checkFinite(name: String, values: FloatArray) {
        for (j in values.indices) {
            if (!values[j].isFinite()) throw TrainingFailedException(iteration, "$name[$j] is ${values[j]}")
        }
    }

    private companion object {
        /** 3DGS's factor on the camera radius. */
        const val EXTENT_MARGIN = 1.1

        /** Below this camera spread (one keyframe, or a tripod), the seed cloud's spread sets the extent. */
        const val MIN_EXTENT = 1e-3

        /** Names of the [TrainState] Adam groups, for the finite check's message. */
        val GROUP_NAMES = listOf("positions", "logScales", "rotations", "opacity", "shDc")

        fun copyOf(c: SplatCloud) = CpuBackend.copyOf(c)

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
