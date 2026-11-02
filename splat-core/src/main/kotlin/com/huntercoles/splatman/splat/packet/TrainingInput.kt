package com.huntercoles.splatman.splat.packet

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SeedPoints

/** Bundle -> trainer packet (ADR 0002). Immutable; lives in memory only. */
data class TrainingInput(
    /** Keyframes at training resolution (long side `config.imageLongSide` px), poses in world frame W, metres. */
    val keyframes: List<Keyframe>,
    /** Seed points, world frame W, metres. */
    val seedPoints: SeedPoints,
    /** How to train. */
    val config: TrainingConfig = TrainingConfig(),
    /** Packet format version. */
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}
