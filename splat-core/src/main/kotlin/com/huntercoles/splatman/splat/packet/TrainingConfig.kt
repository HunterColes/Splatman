package com.huntercoles.splatman.splat.packet

/** How to train (ADR 0002 packet; defaults from ADR 0005). Immutable. */
data class TrainingConfig(
    /** Optimiser iterations, >= 0 (0 = seed splat only). */
    val iterations: Int = DEFAULT_ITERATIONS,
    /** Hard cap on the splat count during densification, splats, > 0. */
    val maxSplats: Int = DEFAULT_MAX_SPLATS,
    /** SH degree trained, 0..3 (v1 trains degree 0). */
    val shDegree: Int = DEFAULT_SH_DEGREE,
    /** Long side of the training images, pixels; keyframes are downscaled to it with their intrinsics scaled. */
    val imageLongSide: Int = DEFAULT_IMAGE_LONG_SIDE,
) {
    init {
        require(iterations >= 0) { "iterations must be >= 0, got $iterations" }
        require(maxSplats > 0) { "maxSplats must be > 0, got $maxSplats" }
        require(shDegree in 0..3) { "shDegree must be in 0..3, got $shDegree" }
        require(imageLongSide > 0) { "imageLongSide must be > 0, got $imageLongSide" }
    }

    companion object {
        /** Packet format version. */
        const val VERSION = 1
        const val DEFAULT_ITERATIONS = 3000
        const val DEFAULT_MAX_SPLATS = 100_000
        const val DEFAULT_SH_DEGREE = 0
        const val DEFAULT_IMAGE_LONG_SIDE = 320
    }
}
