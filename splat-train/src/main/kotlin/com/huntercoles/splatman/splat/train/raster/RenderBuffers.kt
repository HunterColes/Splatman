package com.huntercoles.splatman.splat.train.raster

/**
 * What one [Rasterizer.forward] call writes, kept for the backward pass (ADR 0005): the image, per-pixel final
 * transmittance and contributor count, the projected splats and the per-tile front-to-back lists.
 *
 * Pixels are row-major, index `y * width + x`, OpenCV convention (pixel (x, y) is centred at u = x, v = y).
 * Reuse one instance across calls: the per-splat and per-tile arrays only grow. Not thread-safe.
 */
class RenderBuffers(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
    }

    /** Tiles across, [Rasterizer.TILE] px square; the last column may be partial. */
    val tilesX: Int = (width + Rasterizer.TILE - 1) / Rasterizer.TILE

    /** Tiles down; the last row may be partial. */
    val tilesY: Int = (height + Rasterizer.TILE - 1) / Rasterizer.TILE

    /** `tilesX * tilesY`. */
    val tileCount: Int = tilesX * tilesY

    /** 3 floats per pixel, r, g, b composited front to back over a black background (so premultiplied by alpha). */
    val color = FloatArray(3 * width * height)

    /** Per pixel, `1 - finalT`. */
    val alpha = FloatArray(width * height)

    /** Per pixel, transmittance left after the last splat that contributed. */
    val finalT = FloatArray(width * height)

    /**
     * Per pixel, how many entries of its tile's list were walked up to and including the last one that contributed
     * (0 = none): the backward pass walks `tileSplats[tileStart[t] until tileStart[t] + contributors[p]]` in reverse.
     */
    val contributors = IntArray(width * height)

    /** Number of splats of the last cloud rendered; the per-splat arrays below hold at least this many. */
    var splatCount: Int = 0
        internal set

    /** Per splat: footprint radius in px (ceil of 3 sigma), 0 when culled (behind, too near, degenerate, off image). */
    var radii = IntArray(0)
        internal set

    /** Per splat: view depth, Ccv z in metres (valid where the radius > 0). */
    var depths = FloatArray(0)
        internal set

    /** Per splat: projected centre u, v in px (2 floats). */
    var means2d = FloatArray(0)
        internal set

    /** Per splat: inverse 2D covariance `[[a, b], [b, c]]` as a, b, c in 1/px^2 (3 floats). */
    var conics = FloatArray(0)
        internal set

    /** Per splat: SH degree-0 colour r, g, b, `max(0, 0.5 + C0 * shDc)` (3 floats). */
    var colors = FloatArray(0)
        internal set

    /** Per splat: opacity, sigmoid of its logit. */
    var opacities = FloatArray(0)
        internal set

    /** Per splat: the compositor's skip threshold on the Gaussian's exponent (internal shortcut, see Rasterizer). */
    internal var powerCuts = FloatArray(0)

    /**
     * Tile t's splats, front to back (depth, then index), are `tileSplats[tileStart[t] until tileStart[t + 1]]`;
     * tiles are numbered row-major, `ty * tilesX + tx`.
     */
    val tileStart = IntArray(tileCount + 1)

    /** Splat indices of every tile's list, concatenated in tile order; holds at least `tileStart[tileCount]`. */
    var tileSplats = IntArray(0)
        internal set

    internal fun ensureSplats(n: Int) {
        splatCount = n
        if (radii.size >= n) return
        radii = IntArray(n)
        depths = FloatArray(n)
        means2d = FloatArray(2 * n)
        conics = FloatArray(3 * n)
        colors = FloatArray(3 * n)
        opacities = FloatArray(n)
        powerCuts = FloatArray(n)
    }

    internal fun ensureTileEntries(n: Int) {
        if (tileSplats.size < n) tileSplats = IntArray(n)
    }
}
