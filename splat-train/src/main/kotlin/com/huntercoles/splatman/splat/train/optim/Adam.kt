package com.huntercoles.splatman.splat.train.optim

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adam for one parameter group (ADR 0005), as `torch.optim.Adam` does it for 3DGS: per-element first and second
 * moments, one step count for the whole group, bias-corrected, `p -= lr * m_hat / (sqrt(v_hat) + eps)`.
 *
 * [width] floats belong to each splat, so [remap] can follow densification: kept splats keep their moments, new
 * ones start at zero, and the step count carries on (as 3DGS keeps it when it concatenates new points).
 */
class Adam(
    /** Floats per splat in this group (3 for positions, 4 for rotations, 1 for opacity, ...). */
    val width: Int,
    private val beta1: Double = BETA1,
    private val beta2: Double = BETA2,
    private val eps: Double = EPS,
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
    }

    /** Steps taken so far. */
    var steps: Int = 0
        private set

    private var m = FloatArray(0)
    private var v = FloatArray(0)

    /** One update of the first `width * count` floats of [params] by [grads] at learning rate [lr]. */
    fun step(params: FloatArray, grads: FloatArray, count: Int, lr: Double) {
        val n = width * count
        require(params.size >= n && grads.size >= n) { "params and grads must hold $n floats" }
        if (m.size < n) {
            m = m.copyOf(n)
            v = v.copyOf(n)
        }
        steps++
        val bc1 = 1.0 - beta1.pow(steps)
        val bc2 = 1.0 - beta2.pow(steps)
        for (j in 0 until n) {
            val g = grads[j].toDouble()
            val mj = beta1 * m[j] + (1.0 - beta1) * g
            val vj = beta2 * v[j] + (1.0 - beta2) * g * g
            m[j] = mj.toFloat()
            v[j] = vj.toFloat()
            params[j] = (params[j] - lr * (mj / bc1) / (sqrt(vj / bc2) + eps)).toFloat()
        }
    }

    /**
     * Reorders the moments after densification: new splat j takes old splat `source[j]`'s moments, or zeros where
     * `source[j]` is -1.
     */
    fun remap(source: IntArray) {
        val newM = FloatArray(width * source.size)
        val newV = FloatArray(width * source.size)
        for (j in source.indices) {
            val s = source[j]
            if (s < 0 || width * (s + 1) > m.size) continue
            System.arraycopy(m, width * s, newM, width * j, width)
            System.arraycopy(v, width * s, newV, width * j, width)
        }
        m = newM
        v = newV
    }

    /** First moment of element [j] (tests). */
    internal fun moment1(j: Int): Float = m[j]

    /** Second moment of element [j] (tests). */
    internal fun moment2(j: Int): Float = v[j]

    companion object {
        const val BETA1 = 0.9
        const val BETA2 = 0.999

        /** 3DGS's epsilon. */
        const val EPS = 1e-15
    }
}
