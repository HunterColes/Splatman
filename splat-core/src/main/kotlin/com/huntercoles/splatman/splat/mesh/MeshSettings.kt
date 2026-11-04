package com.huntercoles.splatman.splat.mesh

/** Knobs of the splat-to-mesh export (ADR 0018); the defaults are the ADR's. */
data class MeshSettings(
    /** Grid points per axis of the first density attempt, at most. */
    val maxPointsPerAxis: Int = 256,
    /** The attempt at or under this many points per axis is kept whatever its triangle count. */
    val minPointsPerAxis: Int = 128,
    /** Splats with opacity below this add nothing. */
    val minOpacity: Float = 0.05f,
    /** Iso level of the surface: inside is density >= iso. */
    val iso: Float = 0.5f,
    /** Half-size of each splat's box, in its standard deviations along each grid axis. */
    val sigmas: Float = 3f,
    /** Each splat's box is clipped to this many cells each side of its centre. */
    val maxRadiusCells: Int = 5,
    /** Bounds are the per-axis [boundsQuantile] and 1 - [boundsQuantile] quantiles of the kept centres. */
    val boundsQuantile: Float = 0.005f,
    /** Empty layers on each face of the grid, beyond the [maxRadiusCells] reach: they close every surface. */
    val padCells: Int = 2,
    /** Triangle cap: more triangles rebuild the grid coarser. */
    val maxTriangles: Int = 2_000_000,
    /** Worker threads (z-slabs) of the density pass. */
    val threads: Int = Runtime.getRuntime().availableProcessors(),
) {
    init {
        require(minPointsPerAxis in 1..maxPointsPerAxis) {
            "need 1 <= minPointsPerAxis <= maxPointsPerAxis, got $minPointsPerAxis, $maxPointsPerAxis"
        }
        require(minOpacity.isFinite() && minOpacity >= 0f) { "minOpacity must be finite and >= 0, got $minOpacity" }
        require(iso.isFinite() && iso > 0f) { "iso must be finite and > 0, got $iso" }
        require(sigmas.isFinite() && sigmas > 0f) { "sigmas must be finite and > 0, got $sigmas" }
        require(maxRadiusCells >= 1) { "maxRadiusCells must be >= 1, got $maxRadiusCells" }
        require(boundsQuantile >= 0f && boundsQuantile < 0.5f) {
            "boundsQuantile must be in [0, 0.5), got $boundsQuantile"
        }
        require(padCells >= 0) { "padCells must be >= 0, got $padCells" }
        require(maxTriangles >= 1) { "maxTriangles must be >= 1, got $maxTriangles" }
        require(threads >= 1) { "threads must be >= 1, got $threads" }
    }

    /** Grid layers of padding on each face: [padCells] + [maxRadiusCells]. */
    val totalPad: Int get() = padCells + maxRadiusCells
}
