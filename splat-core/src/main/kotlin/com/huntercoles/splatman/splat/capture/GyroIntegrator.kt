package com.huntercoles.splatman.splat.capture

import com.huntercoles.splatman.splat.math.Quat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Integrates gyroscope samples (rad/s about the device axes, timestamps in ns) into an orientation relative to the
 * first sample (#213). Each sample's rate is applied over the step since the previous sample, in the device frame
 * (`q = q * exp(w dt / 2)`). Out-of-order, duplicate and non-finite samples are skipped; a step longer than
 * [MAX_STEP_NS] is not integrated (counted in [gapCount]) and restarts the rate window. Not thread-safe;
 * allocates nothing per sample.
 */
class GyroIntegrator {

    private var qw = 1.0
    private var qx = 0.0
    private var qy = 0.0
    private var qz = 0.0
    private var mw = 1.0
    private var mx = 0.0
    private var my = 0.0
    private var mz = 0.0
    private var hasLast = false
    private var lastNs = 0L

    private val ringT = LongArray(RING)
    private val ringX = FloatArray(RING)
    private val ringY = FloatArray(RING)
    private val ringZ = FloatArray(RING)
    private var ringSize = 0
    private var ringNext = 0

    /** Steps longer than [MAX_STEP_NS] skipped since construction or the last [reset]. */
    var gapCount: Int = 0
        private set

    /** Adds one sample: [x], [y], [z] in rad/s about the device axes at [timestampNs]. */
    fun add(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (hasLast && timestampNs <= lastNs) return
        if (hasLast) {
            val dtNs = timestampNs - lastNs
            if (dtNs > MAX_STEP_NS) {
                gapCount++
                ringSize = 0
            } else {
                rotateBy(x.toDouble(), y.toDouble(), z.toDouble(), dtNs * 1e-9)
            }
        }
        hasLast = true
        lastNs = timestampNs
        ringT[ringNext] = timestampNs
        ringX[ringNext] = x
        ringY[ringNext] = y
        ringZ[ringNext] = z
        ringNext = (ringNext + 1) % RING
        if (ringSize < RING) ringSize++
    }

    /** Orientation of the device relative to the first sample (device-to-reference rotation). */
    fun orientation(): Quat = Quat(qw.toFloat(), qx.toFloat(), qy.toFloat(), qz.toFloat())

    /** Angle in 0..pi between the orientation at the last [mark] (or the first sample) and now. */
    fun rotationSinceMarkRad(): Float {
        val dot = abs(mw * qw + mx * qx + my * qy + mz * qz)
        return (2.0 * acos(min(1.0, dot))).toFloat()
    }

    /** Makes the current orientation the reference of [rotationSinceMarkRad]; call it after taking a keyframe. */
    fun mark() {
        mw = qw
        mx = qx
        my = qy
        mz = qz
    }

    /** Magnitude of the mean rate over the last [RATE_WINDOW_NS] of samples, rad/s; 0 with no samples. */
    fun rateRadS(): Float {
        if (ringSize == 0) return 0f
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var n = 0
        var i = (ringNext - 1 + RING) % RING
        while (n < ringSize && lastNs - ringT[i] <= RATE_WINDOW_NS) {
            sx += ringX[i]
            sy += ringY[i]
            sz += ringZ[i]
            n++
            i = (i - 1 + RING) % RING
        }
        return (sqrt(sx * sx + sy * sy + sz * sz) / n).toFloat()
    }

    /** Starts a new capture: the next sample is the reference again. */
    fun reset() {
        qw = 1.0
        qx = 0.0
        qy = 0.0
        qz = 0.0
        mark()
        hasLast = false
        lastNs = 0L
        ringSize = 0
        ringNext = 0
        gapCount = 0
    }

    private fun rotateBy(x: Double, y: Double, z: Double, dt: Double) {
        val w = sqrt(x * x + y * y + z * z)
        val half = 0.5 * w * dt
        if (half == 0.0) return
        val s = sin(half) / w
        val dw = cos(half)
        val dx = x * s
        val dy = y * s
        val dz = z * s
        val nw = qw * dw - qx * dx - qy * dy - qz * dz
        val nx = qw * dx + qx * dw + qy * dz - qz * dy
        val ny = qw * dy - qx * dz + qy * dw + qz * dx
        val nz = qw * dz + qx * dy - qy * dx + qz * dw
        val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        qw = nw / n
        qx = nx / n
        qy = ny / n
        qz = nz / n
    }

    companion object {
        /** Longest step integrated; a longer gap between samples is skipped. */
        const val MAX_STEP_NS = 100_000_000L

        /** Span of samples averaged by [rateRadS]. */
        const val RATE_WINDOW_NS = 100_000_000L

        /** Samples kept for [rateRadS]: enough for 100 ms at 2.5 kHz. */
        private const val RING = 256
    }
}
