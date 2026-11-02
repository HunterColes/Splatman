package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.processing.ProcessingState

/** What the user did on the Processing screen. */
sealed interface ProcessingIntent {
    /** Start the run again (after a failure, an interrupted run or a cancel). */
    data object Retry : ProcessingIntent

    /** Stop the run; the scan keeps its bundle (and the seed splat, when one was made). */
    data object Cancel : ProcessingIntent
}

/**
 * The Processing screen's state: the scan's [ProcessingState] ([missing] when the route names no scan folder).
 * [fraction] is 0..1 while the trainer reports iterations, else null (an indeterminate bar).
 */
data class ProcessingUiState(
    val scanId: String,
    val state: ProcessingState = ProcessingState.Idle,
    val missing: Boolean = false,
) {
    val fraction: Float? get() = state.fraction()

    /** Retry is offered once nothing runs: after a failure (an interrupted run reads as one) or a cancel. */
    val canRetry: Boolean get() = !missing && (state is ProcessingState.Failed || state == ProcessingState.Cancelled)

    /** Cancel is offered while the run goes or is held for heat. */
    val canCancel: Boolean get() = state is ProcessingState.Running || state is ProcessingState.Paused
}

/** The share of iterations done, or null before the trainer's first report (or when it reports none). */
fun ProcessingState.fraction(): Float? {
    val progress = when (this) {
        is ProcessingState.Running -> progress
        is ProcessingState.Paused -> progress
        else -> null
    } ?: return null
    if (progress.totalIterations <= 0) return null
    return (progress.iteration.toFloat() / progress.totalIterations).coerceIn(0f, 1f)
}
