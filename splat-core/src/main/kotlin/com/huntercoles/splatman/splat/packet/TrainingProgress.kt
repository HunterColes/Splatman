package com.huntercoles.splatman.splat.packet

import com.huntercoles.splatman.splat.model.SplatCloud

/** Trainer -> UI/viewer packet (ADR 0002), emitted on a conflated `Flow`. Immutable. */
data class TrainingProgress(
    /** Iterations done so far, 0..[totalIterations]. */
    val iteration: Int,
    /** Iterations planned for the run. */
    val totalIterations: Int,
    /** Training loss at [iteration], unitless (L1 on 0..1 colour in v1). */
    val loss: Float,
    /** Splats in the current model. */
    val splatCount: Int,
    /** Wall-clock time since training started, milliseconds. */
    val elapsedMs: Long,
    /** A published copy of the current model (world frame W, metres) for the live preview, or null. */
    val snapshot: SplatCloud? = null,
    /** The lens learned so far (ticket #10), or null when the trainer does not refine the camera. */
    val camera: CameraRefinement? = null,
    /** Packet format version. */
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}
