package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.packet.TrainingProgress
import java.io.File

/** Where a scan's processing run stands (ADR 0005 runner). One per scan folder, from [ProcessingRepository]. */
sealed interface ProcessingState {
    /** Not processed yet and nothing running. */
    data object Idle : ProcessingState

    /** Running; [progress] is null while the bundle loads, then the trainer's latest emission. */
    data class Running(val progress: TrainingProgress?) : ProcessingState

    /** Held at the trainer's latest emission because the phone is at `THERMAL_STATUS_SEVERE` or hotter. */
    data class Paused(val progress: TrainingProgress) : ProcessingState

    /** The splat is written to [splat]. */
    data class Done(val splat: File) : ProcessingState

    /** Stopped by the user; the bundle is untouched and no new splat was written. */
    data object Cancelled : ProcessingState

    /** The run failed or was killed mid-way; the bundle is untouched, so it can be started again. */
    data class Failed(val message: String) : ProcessingState
}
