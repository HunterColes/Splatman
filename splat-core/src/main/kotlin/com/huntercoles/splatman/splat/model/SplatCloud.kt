package com.huntercoles.splatman.splat.model

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.exp

/**
 * A 3D Gaussian splat as a struct of arrays of raw parameters (ADR 0002), as the optimiser and the 3DGS PLY hold
 * them; the accessors activate them for readers. All geometry is in the world frame W (ADR 0003: right-handed,
 * +Y up, metres); no frame conversion happens here.
 *
 * The arrays are not copied, neither in nor out: immutable by contract, no code writes into a published cloud's
 * arrays (the trainer publishes copies). The constructor checks every length against [count] and [shDegree].
 */
class SplatCloud(
    /** Number of splats N, >= 0. */
    val count: Int,
    /** Spherical-harmonics degree, 0..3. */
    val shDegree: Int,
    /** 3N floats, centre x, y, z per splat, world frame W, metres. */
    val positions: FloatArray,
    /** 3N floats, natural log of the per-axis standard deviation in the splat's local axes, ln(metres). */
    val logScales: FloatArray,
    /** 4N floats, quaternion w, x, y, z per splat, local -> world W, not necessarily unit. */
    val rotations: FloatArray,
    /** N floats, opacity logit, unitless: opacity = sigmoid(value). */
    val opacityLogits: FloatArray,
    /** 3N floats, degree-0 SH per channel r, g, b, unitless: colour = 0.5 + C0 * value. */
    val shDc: FloatArray,
    /**
     * N * 3 * K floats, K = (shDegree + 1)^2 - 1, unitless SH coefficients in world frame W directions; layout
     * [splat][channel][coeff], index `i * 3 * K + c * K + k`, the PLY `f_rest_*` order.
     */
    val shRest: FloatArray,
) {
    /** K, the SH coefficients per channel beyond degree 0: (shDegree + 1)^2 - 1. */
    val shRestPerChannel: Int

    init {
        require(count >= 0) { "count must be >= 0, got $count" }
        require(shDegree in 0..3) { "shDegree must be in 0..3, got $shDegree" }
        shRestPerChannel = restCoefficients(shDegree)
        checkLength("positions", positions.size, 3 * count)
        checkLength("logScales", logScales.size, 3 * count)
        checkLength("rotations", rotations.size, 4 * count)
        checkLength("opacityLogits", opacityLogits.size, count)
        checkLength("shDc", shDc.size, 3 * count)
        checkLength("shRest", shRest.size, count * 3 * shRestPerChannel)
    }

    /** Centre of splat [i], world frame W, metres. */
    fun position(i: Int): Vec3 = Vec3(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])

    /** Opacity of splat [i], 0..1: sigmoid of its logit. */
    fun opacity(i: Int): Float = 1f / (1f + exp(-opacityLogits[i]))

    /** Per-axis standard deviation of splat [i] in its local axes, metres: exp of its log-scales. */
    fun scale(i: Int): Vec3 = Vec3(exp(logScales[3 * i]), exp(logScales[3 * i + 1]), exp(logScales[3 * i + 2]))

    /** Rotation of splat [i], local -> world W, as a unit Hamilton quaternion; throws on a zero quaternion. */
    fun rotation(i: Int): Quat =
        Quat(rotations[4 * i], rotations[4 * i + 1], rotations[4 * i + 2], rotations[4 * i + 3]).normalized()

    /** Index into [shRest] of splat [i], channel [c] (0 r, 1 g, 2 b), coefficient [k] (0 until K). */
    fun shRestIndex(i: Int, c: Int, k: Int): Int = i * 3 * shRestPerChannel + c * shRestPerChannel + k

    private fun checkLength(name: String, actual: Int, expected: Int) {
        require(actual == expected) {
            "$name must hold $expected floats for count=$count shDegree=$shDegree, got $actual"
        }
    }

    companion object {
        /** Format version of this layout (ADR 0002); bump on any change to the fields or their meaning. */
        const val VERSION = 1

        /** K, the SH coefficients per channel beyond degree 0, for [shDegree] 0..3: 0, 3, 8, 15. */
        fun restCoefficients(shDegree: Int): Int {
            require(shDegree in 0..3) { "shDegree must be in 0..3, got $shDegree" }
            return (shDegree + 1) * (shDegree + 1) - 1
        }
    }
}
