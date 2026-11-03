package com.huntercoles.splatman.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.importing.ImportDialogHost
import com.huntercoles.splatman.library.importing.ImportIntent
import com.huntercoles.splatman.library.importing.ImportViewModel
import com.huntercoles.splatman.library.presentation.LibraryActions
import com.huntercoles.splatman.library.presentation.LibraryScreen

/** `.splat` has no registered MIME type, so the picker shows every file and validation decides. */
private val PICKER_TYPES = arrayOf("*/*")

/**
 * Public API entry point for the library feature: the whole home screen, top bar and FAB included, and the import
 * flow (docs/design/screens/import.md): Open a file launches the system picker, the file is validated and copied into
 * a new imported scan, which opens in the Viewer. Files from "Open with" arrive through `ImportRequests`.
 * [onOpenViewer] gets a READY scan's id or a sample's `sample:<file>` id; [onOpenProcessing] gets the id of a scan
 * that is not built yet or failed. On a phone that cannot track motion the Scan FAB shows [arUnsupportedTitle]
 * with Details, which calls [onArDetails] (the onboarding AR page). Share hands [onShare] the selection's
 * `content://` share files; it answers false when no app can take them.
 */
@Composable
fun LibraryFeature(
    onOpenViewer: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onArDetails: () -> Unit,
    arUnsupportedTitle: String,
    modifier: Modifier = Modifier,
    onShare: (uris: List<String>, fileNames: List<String>) -> Boolean = { _, _ -> false },
    importViewModel: ImportViewModel = hiltViewModel(),
) {
    val importState by importViewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importViewModel.onIntent(ImportIntent.Pick(uri.toString()))
    }
    LaunchedEffect(importState.opened) {
        val opened = importState.opened ?: return@LaunchedEffect
        importViewModel.onIntent(ImportIntent.Opened)
        onOpenViewer(opened.scanId)
    }

    LibraryScreen(
        actions = LibraryActions(
            onOpen = { target ->
                when (target) {
                    is OpenTarget.Viewer -> onOpenViewer(target.scanId)
                    is OpenTarget.Processing -> onOpenProcessing(target.scanId)
                }
            },
            onScan = onScan,
            onOpenFile = { if (!importState.busy) picker.launch(PICKER_TYPES) },
            onSettings = onSettings,
            onAbout = onAbout,
            onArDetails = onArDetails,
            onShare = { files -> onShare(files.uris, files.fileNames) },
        ),
        arUnsupportedTitle = arUnsupportedTitle,
        modifier = modifier,
    )
    ImportDialogHost(importState.dialog, importViewModel::onIntent)
}
