package com.huntercoles.splatman.splat.model

/** Sparse coloured points that seed the splat (ADR 0002, 0005). Arrays are not copied: immutable by contract. */
class SeedPoints(
    /** Number of points N, >= 0. */
    val count: Int,
    /** 3N floats, x, y, z per point, world frame W, metres. */
    val positions: FloatArray,
    /** 3N bytes, r, g, b per point, sRGB 0..255 (read as unsigned). */
    val colours: ByteArray,
) {
    init {
        require(count >= 0) { "count must be >= 0, got $count" }
        require(positions.size == 3 * count) { "positions must hold ${3 * count} floats, got ${positions.size}" }
        require(colours.size == 3 * count) { "colours must hold ${3 * count} bytes, got ${colours.size}" }
    }
}
