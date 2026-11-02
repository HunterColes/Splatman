package com.huntercoles.splatman.splat.train.raster

/**
 * What one [Rasterizer.backward] call writes: the gradient of the loss with respect to every raw parameter of the
 * [com.huntercoles.splatman.splat.model.SplatCloud] it was given, same layout and units as the cloud's arrays
 * (ADR 0002), plus the per-splat screen-space mean gradient used for densification (ADR 0005).
 *
 * Every call overwrites the first [count] entries of every array (splats culled by the forward pass get 0); summing
 * over views is the caller's job. `shRest` has no gradient: colour is SH degree 0 only (ADR 0005, v1).
 * Reuse one instance across calls: the arrays only grow. Not thread-safe.
 */
class SplatGrads {
    /** Number of splats of the last backward call; the arrays below hold at least this many. */
    var count: Int = 0
        private set

    /** 3 per splat: dL / d position x, y, z (world W). */
    var positions = FloatArray(0)
        private set

    /** 3 per splat: dL / d log-scale. */
    var logScales = FloatArray(0)
        private set

    /** 4 per splat: dL / d raw quaternion w, x, y, z (through the normalisation the forward pass applies). */
    var rotations = FloatArray(0)
        private set

    /** 1 per splat: dL / d opacity logit. */
    var opacityLogits = FloatArray(0)
        private set

    /** 3 per splat: dL / d degree-0 SH r, g, b (0 where the colour was clamped at 0). */
    var shDc = FloatArray(0)
        private set

    /**
     * 1 per splat: `|dL / d mean2d|` with the projected centre in NDC (`u = (x_ndc + 1) * width / 2`, the same for v
     * and height), the view-space gradient 3DGS thresholds for densification (2e-4, ADR 0005).
     */
    var viewGradNorms = FloatArray(0)
        private set

    /** dL / d [Lens.focalScale], summed over the visible splats; 0 when the backward call had no lens. */
    var focalScale: Double = 0.0
        internal set

    /** dL / d [Lens.k1], summed over the visible splats; 0 when the backward call had no lens. */
    var k1: Double = 0.0
        internal set

    /** Per splat, [CAMERA] lens gradients (focal scale, k1), summed in index order into [focalScale] and [k1]. */
    internal var splatCamera = DoubleArray(0)
        private set

    /**
     * Per tile-list entry, [ENTRY] partial sums over the tile's pixels: dL / d u, v, conic a, b, c, colour r, g, b,
     * opacity. Written per tile (one thread each), then summed per splat in list order, so results do not depend on
     * the thread count.
     */
    internal var entryGrads = FloatArray(0)
        private set

    /** Per splat, the [ENTRY] screen-space sums of [entryGrads] in Double. */
    internal var splatGrads2d = DoubleArray(0)
        private set

    internal fun ensureSplats(n: Int) {
        count = n
        if (opacityLogits.size < n) {
            positions = FloatArray(3 * n)
            logScales = FloatArray(3 * n)
            rotations = FloatArray(4 * n)
            opacityLogits = FloatArray(n)
            shDc = FloatArray(3 * n)
            viewGradNorms = FloatArray(n)
            splatGrads2d = DoubleArray(ENTRY * n)
            splatCamera = DoubleArray(CAMERA * n)
        }
        positions.fill(0f, 0, 3 * n)
        logScales.fill(0f, 0, 3 * n)
        rotations.fill(0f, 0, 4 * n)
        opacityLogits.fill(0f, 0, n)
        shDc.fill(0f, 0, 3 * n)
        viewGradNorms.fill(0f, 0, n)
        splatGrads2d.fill(0.0, 0, ENTRY * n)
        splatCamera.fill(0.0, 0, CAMERA * n)
        focalScale = 0.0
        k1 = 0.0
    }

    internal fun ensureEntries(n: Int) {
        if (entryGrads.size < ENTRY * n) entryGrads = FloatArray(ENTRY * n)
    }

    internal companion object {
        /** Screen-space gradient values per entry: u, v, conic a, b, c, colour r, g, b, opacity. */
        const val ENTRY = 9
        const val U = 0
        const val V = 1
        const val CA = 2
        const val CB = 3
        const val CC = 4
        const val R = 5
        const val OP = 8

        /** Lens gradient values per splat: focal scale, k1. */
        const val CAMERA = 2
    }
}
