package com.huntercoles.splatman.viewer.rendering.gl

/**
 * Picks the offscreen render scale from frame times (ADR 0006): once [WINDOW] frames are in, a mean above
 * [SLOW_MS] drops the scale by [STEP] (not below [MIN_SCALE]) and a mean below [FAST_MS] raises it (not above
 * [MAX_SCALE]); each verdict starts a fresh window, so a change waits for 30 frames drawn at the current scale.
 * Pure state, called on the GL thread only.
 */
class RenderScaleController(initial: Float = MAX_SCALE) {
    var scale: Float = initial.coerceIn(MIN_SCALE, MAX_SCALE)
        private set

    private var sumMs = 0.0
    private var frames = 0

    /** Records one frame of [frameMs]; returns the scale for the next frame. */
    fun onFrame(frameMs: Float): Float {
        if (!frameMs.isFinite() || frameMs < 0f) return scale
        sumMs += frameMs
        frames++
        if (frames < WINDOW) return scale
        val mean = sumMs / frames
        sumMs = 0.0
        frames = 0
        scale = when {
            mean > SLOW_MS -> (scale - STEP).coerceAtLeast(MIN_SCALE)
            mean < FAST_MS -> (scale + STEP).coerceAtMost(MAX_SCALE)
            else -> scale
        }
        return scale
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.0f
        const val STEP = 0.1f
        const val WINDOW = 30
        const val SLOW_MS = 20.0
        const val FAST_MS = 12.0
    }
}
