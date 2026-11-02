package com.huntercoles.splatman.splat.train.optim

/**
 * Stage 2 stopped on a bad model, not on a bug: the cloud is empty (every splat pruned), or a parameter went NaN or
 * infinite. [iteration] is the one that produced it (0 = the starting cloud). The stage-1 seed cloud, which
 * [com.huntercoles.splatman.splat.train.CpuTrainer] emits first, is the fallback (ADR 0005).
 */
class TrainingFailedException(
    /** Iteration that produced the failure, 1-based; 0 for the starting cloud. */
    val iteration: Int,
    /** What went wrong. */
    val reason: String,
) : RuntimeException("training failed at iteration $iteration: $reason")
