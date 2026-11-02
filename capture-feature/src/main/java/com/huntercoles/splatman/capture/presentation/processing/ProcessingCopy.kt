package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.FailureReason

/** The `proc_failed_*` line for a failure [reason]. */
fun FailureReason.copy(): Int = when (this) {
    FailureReason.TRACKING -> R.string.proc_failed_tracking
    FailureReason.FRAMES -> R.string.proc_failed_frames
    FailureReason.MEMORY -> R.string.proc_failed_memory
    FailureReason.STORAGE -> R.string.proc_failed_storage
    FailureReason.UNKNOWN -> R.string.proc_failed_unknown
}
