package com.huntercoles.splatman.splat.gpu.optim

import com.huntercoles.splatman.splat.gpu.ComputeProgram
import com.huntercoles.splatman.splat.gpu.GlesCompute
import com.huntercoles.splatman.splat.gpu.Ssbo
import com.huntercoles.splatman.splat.gpu.Std430
import com.huntercoles.splatman.splat.gpu.Std430Type
import com.huntercoles.splatman.splat.train.optim.Adam
import kotlin.math.pow

/** The float uniforms of one Adam step, each computed in Double and rounded once, as `Adam.step` uses them. */
data class AdamUniforms(
    val lr: Float,
    val beta1: Float,
    val beta2: Float,
    val oneMinusBeta1: Float,
    val oneMinusBeta2: Float,
    val eps: Float,
    val bc1: Float,
    val bc2: Float,
) {
    companion object {
        /** The uniforms of step number [step] (1 for the first) at learning rate [lr]. */
        fun of(beta1: Double, beta2: Double, eps: Double, step: Int, lr: Double): AdamUniforms {
            require(step >= 1) { "step must be >= 1, was $step" }
            return AdamUniforms(
                lr = lr.toFloat(),
                beta1 = beta1.toFloat(),
                beta2 = beta2.toFloat(),
                oneMinusBeta1 = (1.0 - beta1).toFloat(),
                oneMinusBeta2 = (1.0 - beta2).toFloat(),
                eps = eps.toFloat(),
                bc1 = (1.0 - beta1.pow(step)).toFloat(),
                bc2 = (1.0 - beta2.pow(step)).toFloat(),
            )
        }
    }
}

/**
 * Adam for one parameter group on the GPU ([AdamShaders.ADAM]), `Adam.step` with its moments in SSBOs: one dispatch per
 * [step], one step count for the group. [width] floats belong to each splat. Made and used on the GL thread.
 */
class GpuAdam(
    private val gl: GlesCompute,
    val width: Int,
    private val beta1: Double = Adam.BETA1,
    private val beta2: Double = Adam.BETA2,
    private val eps: Double = Adam.EPS,
) : AutoCloseable {
    init {
        require(width > 0) { "width must be > 0, got $width" }
    }

    /** Steps taken so far. */
    var steps: Int = 0
        private set

    private val program: ComputeProgram = gl.program(AdamShaders.ADAM)
    private var m: Ssbo? = null
    private var v: Ssbo? = null

    /** Floats the moment buffers hold (zeros past what was stepped). */
    private var capacity = 0

    /** One update of the first `width * count` floats of [params] by [grads] at learning rate [lr]. */
    fun step(params: Ssbo, grads: Ssbo, count: Int, lr: Double) {
        val n = width * count
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, n)
        require(params.sizeBytes >= bytes && grads.sizeBytes >= bytes) { "params and grads must hold $n floats" }
        ensure(n)
        steps++
        if (n == 0) return
        val u = AdamUniforms.of(beta1, beta2, eps, steps, lr)
        params.bind(AdamShaders.B_PARAMS)
        grads.bind(AdamShaders.B_GRADS)
        m!!.bind(AdamShaders.B_M)
        v!!.bind(AdamShaders.B_V)
        program.setUInt("n", n)
        program.setFloat("lr", u.lr)
        program.setFloat("beta1", u.beta1)
        program.setFloat("beta2", u.beta2)
        program.setFloat("oneMinusBeta1", u.oneMinusBeta1)
        program.setFloat("oneMinusBeta2", u.oneMinusBeta2)
        program.setFloat("eps", u.eps)
        program.setFloat("bc1", u.bc1)
        program.setFloat("bc2", u.bc2)
        program.dispatchFor(n, AdamShaders.LOCAL)
    }

    /** Replaces the moments and the step count (a `TrainState` being loaded); the arrays are uploaded. */
    fun restore(m: FloatArray, v: FloatArray, steps: Int) {
        require(m.size == v.size && m.size % width == 0) { "m and v must hold the same multiple of $width floats" }
        require(steps >= 0) { "steps must be >= 0, got $steps" }
        allocate(m.size)
        if (m.isNotEmpty()) {
            this.m!!.upload(m)
            this.v!!.upload(v)
        }
        this.steps = steps
    }

    /** The first moments of [count] splats, zeros where none were taken yet. */
    fun firstMoments(count: Int): FloatArray = moments(m, count)

    /** The second moments of [count] splats, zeros where none were taken yet. */
    fun secondMoments(count: Int): FloatArray = moments(v, count)

    override fun close() {
        program.close()
        m?.close()
        v?.close()
        m = null
        v = null
    }

    private fun moments(buf: Ssbo?, count: Int): FloatArray {
        val n = width * count
        val have = minOf(n, capacity)
        val out = FloatArray(n)
        if (have > 0) buf!!.downloadFloats(have).copyInto(out)
        return out
    }

    /** Grows the moments to [n] floats, keeping the old ones and zeroing the rest. */
    private fun ensure(n: Int) {
        if (n <= capacity) return
        val oldM = firstMoments(capacity / width)
        val oldV = secondMoments(capacity / width)
        allocate(n)
        m!!.upload(oldM.copyOf(n))
        v!!.upload(oldV.copyOf(n))
    }

    /** Fresh moment buffers of [n] floats, zeroed. */
    private fun allocate(n: Int) {
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, maxOf(n, 1))
        val zeros = FloatArray(maxOf(n, 1))
        val mm = m ?: gl.ssbo(bytes).also { m = it }
        val vv = v ?: gl.ssbo(bytes).also { v = it }
        if (mm.sizeBytes != bytes) mm.resize(bytes)
        if (vv.sizeBytes != bytes) vv.resize(bytes)
        mm.upload(zeros)
        vv.upload(zeros)
        capacity = n
    }
}

/**
 * The densifier's view-gradient statistics on the GPU ([AdamShaders.STATS]): `gradSum += viewGradNorms` and
 * `gradViews++` for each splat whose radius is > 0, as `CpuBackend.step` does. Made and used on the GL thread.
 */
class GpuGradStats(gl: GlesCompute) : AutoCloseable {
    private val program: ComputeProgram = gl.program(AdamShaders.STATS)

    /** One view's update of the first [count] splats: [radii] int, [viewGradNorms] float, [gradSum] float, [gradViews] int. */
    fun accumulate(radii: Ssbo, viewGradNorms: Ssbo, gradSum: Ssbo, gradViews: Ssbo, count: Int) {
        val bytes = Std430.arrayBytes(Std430Type.FLOAT, count)
        require(listOf(radii, viewGradNorms, gradSum, gradViews).all { it.sizeBytes >= bytes }) {
            "every buffer must hold $count values"
        }
        if (count == 0) return
        radii.bind(AdamShaders.B_RADII)
        viewGradNorms.bind(AdamShaders.B_VIEW_GRAD_NORMS)
        gradSum.bind(AdamShaders.B_GRAD_SUM)
        gradViews.bind(AdamShaders.B_GRAD_VIEWS)
        program.setUInt("n", count)
        program.dispatchFor(count, AdamShaders.LOCAL)
    }

    override fun close() = program.close()
}
