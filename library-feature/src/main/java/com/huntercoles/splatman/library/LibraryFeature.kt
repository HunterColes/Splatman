package com.huntercoles.splatman.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.presentation.LibraryScreen

/**
 * Public API entry point for the library feature. [onOpenViewer] gets a READY scan's id or a sample's
 * `sample:<file>` id; [onOpenProcessing] gets the id of a scan that is not built yet or failed.
 */
@Composable
fun LibraryFeature(
    onOpenViewer: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryScreen(
        onOpen = { target ->
            when (target) {
                is OpenTarget.Viewer -> onOpenViewer(target.scanId)
                is OpenTarget.Processing -> onOpenProcessing(target.scanId)
            }
        },
        modifier = modifier,
    )
}
