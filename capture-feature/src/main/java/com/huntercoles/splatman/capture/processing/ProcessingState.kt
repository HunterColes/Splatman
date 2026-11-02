package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.packet.TrainingProgress
import java.io.File

/** Where a scan's processing run stands (ADR 0005 runner). One per scan folder, from [ProcessingRepository]. */
sealed interface ProcessingState {
    /** Not processed yet and nothing running. */
    data object Idle : ProcessingState

    /**
     * Running. While the bundle loads [progress] is null and [prepared] is the share of keyframes decoded (0..1);
     * then [progress] is the trainer's latest emission; [saving] is true once training ended and the splat is written.
     */
    data class Running(
        val progress: TrainingProgress?,
        val prepared: Float = 0f,
        val saving: Boolean = false,
    ) : ProcessingState

    /** Held at the trainer's latest emission because the phone is at `THERMAL_STATUS_SEVERE` or hotter. */
    data class Paused(val progress: TrainingProgress) : ProcessingState

    /** The splat is written to [splat]. */
    data class Done(val splat: File) : ProcessingState

    /** Stopped by the user; the bundle is untouched and no new splat was written. */
    data object Cancelled : ProcessingState

    /**
     * The run failed or was killed mid-way; the bundle is untouched, so it can be started again. [message] is the
     * technical text (the Details row), [reason] what the user is told, [needBytes] the space to free for
     * [FailureReason.STORAGE].
     */
    data class Failed(
        val message: String,
        val reason: FailureReason = FailureReason.UNKNOWN,
        val needBytes: Long = 0,
    ) : ProcessingState
}

/** Why a run failed, as the user is told it (docs/design/screens/processing.md, `proc_failed_*`). */
enum class FailureReason { TRACKING, FRAMES, MEMORY, STORAGE, UNKNOWN }
