package com.huntercoles.splatman.capture.presentation.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The time-left line: unknown, a whole number of minutes, or almost done. */
sealed interface Eta {
    data object Unknown : Eta
    data class Minutes(val minutes: Int) : Eta
    data object AlmostDone : Eta
}

/**
 * The ETA rule (docs/design/screens/processing.md): [Eta.Unknown] until progress passes 10 % and the estimate has been
 * stable for 10 s, then whole minutes ("About 1 min" below 90 s, "Almost done" below 15 s). It never counts up.
 * While the phone is hot it hides, and must be stable for 10 s again once it cools. One per run; not thread-safe.
 *
 * The estimate is the rate since the first sample: remaining = (1 - f) / ((f - f0) / (t - t0)). It is stable while
 * the predicted finish time stays within [TOLERANCE] (or [MIN_TOLERANCE_MS]) of where it was when it settled.
 */
class EtaEstimator {
    private var firstMs = -1L
    private var firstFraction = 0f
    private var anchorFinishMs = 0L
    private var anchorSinceMs = -1L
    private var shownSeconds = Long.MAX_VALUE

    /** Feeds one sample: overall [fraction] 0..1 at [nowMs]; [hot] while the phone is held for heat. */
    fun update(fraction: Float, nowMs: Long, hot: Boolean = false): Eta {
        if (firstMs < 0) {
            firstMs = nowMs
            firstFraction = fraction
        }
        val remainingMs = estimate(fraction, nowMs)
        if (hot || remainingMs == null) {
            anchorSinceMs = -1L
            return Eta.Unknown
        }
        val finish = nowMs + remainingMs
        val tolerance = max(MIN_TOLERANCE_MS, (remainingMs * TOLERANCE).toLong())
        if (anchorSinceMs < 0 || abs(finish - anchorFinishMs) > tolerance) {
            anchorFinishMs = finish
            anchorSinceMs = nowMs
        }
        if (fraction <= MIN_FRACTION || nowMs - anchorSinceMs < STABLE_MS) return Eta.Unknown
        shownSeconds = minOf(shownSeconds, remainingMs / MS_PER_S)
        return display(shownSeconds)
    }

    private fun estimate(fraction: Float, nowMs: Long): Long? {
        val gained = fraction - firstFraction
        val spent = nowMs - firstMs
        if (gained <= 0f || spent <= 0L) return null
        return ((1f - fraction).coerceAtLeast(0f) * spent / gained).toLong()
    }

    companion object {
        const val MIN_FRACTION = 0.10f
        const val STABLE_MS = 10_000L
        const val TOLERANCE = 0.2f
        const val MIN_TOLERANCE_MS = 15_000L
        private const val MS_PER_S = 1000L
        private const val ALMOST_DONE_S = 15L
        private const val ONE_MINUTE_BELOW_S = 90L
        private const val S_PER_MIN = 60f

        /** Whole minutes ("About 1 min" below 90 s), "Almost done" below 15 s. */
        fun display(seconds: Long): Eta = when {
            seconds < ALMOST_DONE_S -> Eta.AlmostDone
            seconds < ONE_MINUTE_BELOW_S -> Eta.Minutes(1)
            else -> Eta.Minutes((seconds / S_PER_MIN).roundToInt())
        }
    }
}
