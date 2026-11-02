package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens

/** Learning rates of one iteration; positions are already multiplied by the scene extent. */
data class StepRates(
    val positions: Double,
    val logScales: Double,
    val rotations: Double,
    val opacity: Double,
    val shDc: Double,
)

/**
 * The optimiser's per-splat state: the cloud, Adam m and v per group (same layout as the cloud's arrays), Adam's step
 * count, and the summed view-space gradient norms and view counts for densification.
 * Groups in order positions, logScales, rotations, opacity, shDc ([GROUP_WIDTHS] floats per splat).
 */
class TrainState(
    val cloud: SplatCloud,
    val adamM: List<FloatArray>,
    val adamV: List<FloatArray>,
    val adamSteps: Int,
    val gradSum: FloatArray,
    val gradViews: IntArray,
) {
    init {
        require(adamM.size == GROUP_WIDTHS.size && adamV.size == GROUP_WIDTHS.size) {
            "adamM and adamV need ${GROUP_WIDTHS.size} groups"
        }
        for (g in GROUP_WIDTHS.indices) {
            val n = GROUP_WIDTHS[g] * cloud.count
            require(adamM[g].size == n && adamV[g].size == n) { "Adam group $g must hold $n floats" }
        }
        require(gradSum.size == cloud.count && gradViews.size == cloud.count) { "gradSum and gradViews need one per splat" }
    }

    companion object {
        /** Floats per splat of each Adam group: positions, logScales, rotations, opacity, shDc. */
        val GROUP_WIDTHS: IntArray = intArrayOf(3, 3, 4, 1, 3)

        /** A fresh state for [cloud]: zero moments, no Adam steps, no gradient statistics. The cloud is not copied. */
        fun fresh(cloud: SplatCloud): TrainState = TrainState(
            cloud,
            GROUP_WIDTHS.map { FloatArray(it * cloud.count) },
            GROUP_WIDTHS.map { FloatArray(it * cloud.count) },
            0,
            FloatArray(cloud.count),
            IntArray(cloud.count),
        )
    }
}

/**
 * Where one stage-2 iteration runs: forward, L1, backward, Adam and the gradient statistics. [SplatOptimizer] keeps
 * the schedule (keyframe draw, learning rates, densification, finite checks). [CpuBackend] is the reference.
 */
interface TrainBackend : AutoCloseable {
    /** Replaces the whole state; after init and after each densify. */
    fun load(state: TrainState)

    /** One iteration on keyframes[[keyframe]]; returns its L1 loss. */
    fun step(keyframe: Int, rates: StepRates): Float

    /** Copies of the state, for densify, snapshot and the finite check. */
    fun read(): TrainState

    /**
     * Whether [step] can render through a learned [Lens] and report its gradient ([lensGrad]). [SplatOptimizer] learns
     * the lens only on a backend that can; on any other, the keyframes' pinholes are kept.
     */
    val learnsLens: Boolean get() = false

    /** The lens the following steps render through and differentiate; null = the keyframes' pinholes. */
    fun setLens(lens: Lens?) {
        require(lens == null) { "this backend does not learn the lens" }
    }

    /** dL / d focal scale and dL / d k1 of the last [step], 0 when it had no lens. */
    fun lensGrad(): LensGrad = throw UnsupportedOperationException("this backend does not learn the lens")
}

/** The lens gradient of one step. */
data class LensGrad(val focalScale: Double, val k1: Double)
