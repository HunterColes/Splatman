package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.ProcessingState
import java.io.File

/** What the user did on the Processing screen. */
sealed interface ProcessingIntent {
    /** Start the run again (after a failure, an interrupted run or a cancel). */
    data object Retry : ProcessingIntent

    /** Stop the run (confirmed in the dialog); the scan keeps its bundle. */
    data object Cancel : ProcessingIntent

    /** Open the finished splat in the Viewer. */
    data object Open : ProcessingIntent

    /** Share the finished splat. */
    data object Share : ProcessingIntent

    /** Delete the scan (Failed state). */
    data object Delete : ProcessingIntent

    /** Back: leave the screen, the run keeps going. */
    data object Leave : ProcessingIntent

    /** The notification question was answered (either way); now leave. */
    data object NotificationAsked : ProcessingIntent
}

/** One-off things the screen does. */
sealed interface ProcessingEvent {
    data class OpenViewer(val scanId: String) : ProcessingEvent

    data class Share(val uri: String, val fileName: String) : ProcessingEvent

    /** Ask for `POST_NOTIFICATIONS` with `proc_leave` as the reason, then send [ProcessingIntent.NotificationAsked]. */
    data object AskNotifications : ProcessingEvent

    /** Go back (after Back, a cancel or a delete). */
    data object Leave : ProcessingEvent

    /** The `CONFIRM` haptic (the splat is ready). */
    data object Haptic : ProcessingEvent
}

/**
 * The Processing screen's state: the scan's [ProcessingState] ([missing] when the route names no scan folder), the
 * [eta] line, whether the frames are still on the phone ([framesKept]; Try again needs them) and the capture's
 * [lastFrame] (the preview placeholder), when there is one.
 */
data class ProcessingUiState(
    val scanId: String,
    val state: ProcessingState = ProcessingState.Idle,
    val missing: Boolean = false,
    val eta: Eta = Eta.Unknown,
    val framesKept: Boolean = true,
    val lastFrame: File? = null,
) {
    /** The stage and overall progress, or null when nothing runs (an indeterminate bar while starting). */
    val progress: StageProgress? get() = state.stageProgress()

    /** True while the phone is held for heat. */
    val hot: Boolean get() = state is ProcessingState.Paused

    /** Failed, or cancelled (which reads as Failed "Stopped"). */
    val failed: Boolean get() = !missing && (state is ProcessingState.Failed || state == ProcessingState.Cancelled)

    /** Try again is offered after a failure or a cancel, only while the frames are kept. */
    val canRetry: Boolean get() = failed && framesKept

    /** Cancel is offered while the run starts, goes or is held for heat. */
    val canCancel: Boolean
        get() = !missing &&
            (state == ProcessingState.Idle || state is ProcessingState.Running || state is ProcessingState.Paused)

    val done: Boolean get() = !missing && state is ProcessingState.Done

    /** The `proc_failed_*` line: "Stopped" after a cancel, else the failure reason's copy; null unless failed. */
    val failureCopy: Int?
        get() {
            if (missing) return null
            return when (val s = state) {
                ProcessingState.Cancelled -> R.string.proc_failed_stopped
                is ProcessingState.Failed -> s.reason.copy()
                else -> null
            }
        }
}
