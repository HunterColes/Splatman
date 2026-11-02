package com.huntercoles.splatman.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.presentation.LibraryActions
import com.huntercoles.splatman.library.presentation.LibraryScreen

/**
 * Public API entry point for the library feature: the whole home screen, top bar and FAB included.
 * [onOpenViewer] gets a READY scan's id or a sample's `sample:<file>` id; [onOpenProcessing] gets the id of a scan
 * that is not built yet or failed; [onOpenFile] is the Open-a-file entry (the import flow, #21, owns what follows).
 */
@Composable
fun LibraryFeature(
    onOpenViewer: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
    onScan: () -> Unit,
    onOpenFile: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryScreen(
        actions = LibraryActions(
            onOpen = { target ->
                when (target) {
                    is OpenTarget.Viewer -> onOpenViewer(target.scanId)
                    is OpenTarget.Processing -> onOpenProcessing(target.scanId)
                }
            },
            onScan = onScan,
            onOpenFile = onOpenFile,
            onSettings = onSettings,
            onAbout = onAbout,
        ),
        modifier = modifier,
    )
}
