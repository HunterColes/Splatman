package com.huntercoles.splatman.splat.packet

import com.huntercoles.splatman.splat.model.SplatCloud

/** Trainer -> library packet (ADR 0002), written as PLY. Immutable. */
data class TrainingResult(
    /** The final model, world frame W, metres. */
    val cloud: SplatCloud,
    /** Iterations run (fewer than planned when cancelled). */
    val iterations: Int,
    /** Loss at the last iteration, unitless (L1 on 0..1 colour in v1). */
    val finalLoss: Float,
    /** Wall-clock training time, milliseconds. */
    val elapsedMs: Long,
    /** The lens learned so far (ticket #10), or null when the trainer does not refine the camera. */
    val camera: CameraRefinement? = null,
    /** Packet format version. */
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}
