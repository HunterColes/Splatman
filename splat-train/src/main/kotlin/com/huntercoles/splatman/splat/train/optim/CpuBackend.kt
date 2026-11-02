package com.huntercoles.splatman.splat.train.optim

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.train.raster.Lens
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import com.huntercoles.splatman.splat.train.raster.RenderBuffers
import com.huntercoles.splatman.splat.train.raster.SplatGrads
import kotlin.math.abs

/**
 * The reference [TrainBackend]: [Rasterizer] forward and backward, the L1 loss, and [Adam] per group, in place on the
 * loaded cloud. The [rasterizer] stays the caller's; [close] does not close it. Not thread-safe.
 */
class CpuBackend(
    private val keyframes: List<Keyframe>,
    private val rasterizer: Rasterizer,
) : TrainBackend {

    private val targets: List<FloatArray> = keyframes.map { kf ->
        val bytes = kf.image.rgba
        FloatArray(3 * kf.image.width * kf.image.height) { j -> (bytes[4 * (j / 3) + j % 3].toInt() and 0xFF) / 255f }
    }
    private val buffers = HashMap<Long, RenderBuffers>()
    private val grads = SplatGrads()
    private var dLdColor = FloatArray(0)

    private val adams = TrainState.GROUP_WIDTHS.map { Adam(it) }
    private var gradSum = FloatArray(0)
    private var gradViews = IntArray(0)
    private var lens: Lens? = null

    /** The live working cloud, written in place by [step]; [SplatOptimizer] checks it without a copy. */
    internal var cloud: SplatCloud = SplatCloud(0, 0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        private set

    override val learnsLens: Boolean get() = true

    override fun load(state: TrainState) {
        cloud = copyOf(state.cloud)
        for (g in adams.indices) adams[g].restore(state.adamM[g], state.adamV[g], state.adamSteps)
        gradSum = state.gradSum.copyOf()
        gradViews = state.gradViews.copyOf()
    }

    override fun step(keyframe: Int, rates: StepRates): Float {
        val kf = keyframes[keyframe]
        val target = targets[keyframe]
        val out = buffers.getOrPut(kf.image.width.toLong() shl 32 or kf.image.height.toLong()) {
            RenderBuffers(kf.image.width, kf.image.height)
        }
        rasterizer.forward(cloud, kf.intrinsics, kf.pose, out, lens)
        val loss = l1(out.color, target)
        rasterizer.backward(cloud, kf.intrinsics, kf.pose, out, dLdColor, grads, lens)

        val n = cloud.count
        for (i in 0 until n) {
            if (out.radii[i] > 0) {
                gradSum[i] += grads.viewGradNorms[i]
                gradViews[i]++
            }
        }
        adams[0].step(cloud.positions, grads.positions, n, rates.positions)
        adams[1].step(cloud.logScales, grads.logScales, n, rates.logScales)
        adams[2].step(cloud.rotations, grads.rotations, n, rates.rotations)
        adams[3].step(cloud.opacityLogits, grads.opacityLogits, n, rates.opacity)
        adams[4].step(cloud.shDc, grads.shDc, n, rates.shDc)
        return loss
    }

    override fun read(): TrainState {
        val n = cloud.count
        return TrainState(
            copyOf(cloud),
            adams.map { it.firstMoments(n) },
            adams.map { it.secondMoments(n) },
            adams[0].steps,
            gradSum.copyOf(),
            gradViews.copyOf(),
        )
    }

    override fun setLens(lens: Lens?) {
        this.lens = lens
    }

    override fun lensGrad(): LensGrad = LensGrad(grads.focalScale, grads.k1)

    override fun close() = Unit

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

    internal companion object {
        fun copyOf(c: SplatCloud) = SplatCloud(
            c.count, c.shDegree, c.positions.copyOf(), c.logScales.copyOf(), c.rotations.copyOf(),
            c.opacityLogits.copyOf(), c.shDc.copyOf(), c.shRest.copyOf(),
        )
    }
}
