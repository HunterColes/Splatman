package com.huntercoles.splatman.library.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.openTarget

private const val NAME_MAX_LINES = 2

/**
 * The scans on this phone, newest first, then the bundled samples. A READY scan or a sample opens the Viewer, any
 * other scan opens Processing; long-press a scan to rename or delete it. Plain until #15 redraws it.
 */
@Composable
fun LibraryScreen(
    onOpen: (OpenTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Scan?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(SplatDimens.LibraryCellMin),
        modifier = modifier,
        contentPadding = PaddingValues(SplatDimens.SpacingDefault),
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.lib_your_scans)) }
        if (!state.loading && state.scans.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyScans() }
        }
        items(state.scans, key = { it.id }) { scan ->
            LibraryCard(
                name = scan.name,
                detail = scanDetail(scan),
                onClick = { onOpen(scan.openTarget()) },
                onLongClick = { editing = scan },
            )
        }
        if (state.samples.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.lib_samples)) }
            items(state.samples, key = { it.id }) { sample ->
                LibraryCard(
                    name = sample.name,
                    detail = sample.fileName,
                    onClick = { onOpen(OpenTarget.Viewer(sample.id)) },
                )
            }
        }
    }

    editing?.let { scan ->
        EditScanDialog(
            scan = scan,
            onRename = { name -> viewModel.rename(scan.id, name) },
            onDelete = { viewModel.delete(scan.id) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun scanDetail(scan: Scan): String = when (scan.status) {
    ScanStatus.READY -> pluralStringResource(R.plurals.lib_splats, scan.splatCount, scan.splatCount)
    ScanStatus.PROCESSING -> stringResource(R.string.lib_status_processing)
    ScanStatus.CAPTURED -> stringResource(R.string.lib_status_captured)
    ScanStatus.FAILED -> stringResource(R.string.lib_failed)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = SplatDimens.SpacingSmall)
            .semantics { heading() },
    )
}

@Composable
private fun EmptyScans() {
    Column(verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
        Text(stringResource(R.string.lib_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.lib_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(name: String, detail: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(SplatDimens.CornerMedium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SplatDimens.TouchTarget)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(SplatDimens.SpacingMedium)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = NAME_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditScanDialog(scan: Scan, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(scan.id) { mutableStateOf(scan.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.lib_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.lib_rename)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.lib_delete), color = MaterialTheme.colorScheme.error) }
        },
    )
}
