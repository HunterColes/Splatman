package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.capture.presentation.processing.percent
import com.huntercoles.splatman.capture.presentation.processing.stageProgress
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.ShellRoutes

/**
 * What the processing notification says for a state (docs/design/screens/processing.md, Notification): ongoing with
 * a determinate bar and Cancel while running, `notif_done` (tap opens the Viewer) or `notif_failed` (tap opens
 * Processing) once it ended. A cancel posts nothing: the user did it.
 */
sealed interface ProcessingNotice {
    /** Running; [percent] is null (an indeterminate bar) until there is progress; [hot] while held for heat. */
    data class Running(val percent: Int?, val hot: Boolean = false) : ProcessingNotice

    data class Done(val tapRoute: String) : ProcessingNotice

    data class Failed(val tapRoute: String) : ProcessingNotice

    data object None : ProcessingNotice

    companion object {
        fun of(scanId: String, state: ProcessingState): ProcessingNotice = when (state) {
            ProcessingState.Idle -> Running(null)
            is ProcessingState.Running -> Running(state.stageProgress()?.percent())
            is ProcessingState.Paused -> Running(state.stageProgress()?.percent(), hot = true)
            is ProcessingState.Done -> Done(ShellRoutes.path(NavigationDestination.Viewer(scanId)).orEmpty())
            is ProcessingState.Failed -> Failed(ShellRoutes.path(NavigationDestination.Processing(scanId)).orEmpty())
            ProcessingState.Cancelled -> None
        }
    }
}
