package com.huntercoles.splatman.splat.sfm

/** Recovers camera poses and a sparse seed from frames and IMU (epic #200, ADR 0012). */
interface PoseRecovery {
    /** Blocking; the caller picks the thread; checks [isCancelled] between stages and reports each via [onStage]. */
    fun recover(input: SfmInput, onStage: (SfmStage) -> Unit = {}, isCancelled: () -> Boolean = { false }): SfmResult
}
