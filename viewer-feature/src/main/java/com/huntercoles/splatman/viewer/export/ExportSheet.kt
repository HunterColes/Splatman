package com.huntercoles.splatman.viewer.export

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatTheme
import com.huntercoles.splatman.viewer.R
import timber.log.Timber

private const val WIDE_DP = 600
private val DialogMaxWidth = 560.dp
private val RowMinHeight = 56.dp

/**
 * The export sheet (docs/design/screens/export.md): format radio rows with their size, Share through the Android
 * share sheet (FileProvider), Save to device through `CreateDocument` (or a folder through `OpenDocumentTree` for
 * several scans), a writing state Back cancels, an error row and the low-storage check. A modal bottom sheet, a
 * dialog at [WIDE_DP] dp and up; [forceDark] over the Viewer. [onSaved] gets the snackbar text after a save.
 */
@Composable
fun ExportSheet(viewModel: ExportViewModel, onSaved: (String) -> Unit, forceDark: Boolean = true) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportCodecs.MIME)) { uri ->
        viewModel.onIntent(ExportIntent.SaveTo(uri?.toString()))
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onIntent(ExportIntent.SaveToFolder(uri?.toString()))
    }

    LaunchedEffect(state.share) {
        val share = state.share ?: return@LaunchedEffect
        try {
            context.startActivity(AndroidExportTarget.shareIntent(share))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app to share %s", share.fileNames)
        }
        viewModel.onIntent(ExportIntent.ShareLaunched)
    }
    LaunchedEffect(state.saved) {
        val names = state.saved ?: return@LaunchedEffect
        val res = context.resources
        onSaved(
            if (names.size == 1) {
                res.getString(R.string.exp_saved, names.single())
            } else {
                res.getQuantityString(R.plurals.exp_saved_many, names.size, names.size)
            },
        )
        viewModel.onIntent(ExportIntent.SavedShown)
    }

    if (!state.sheetOpen) return
    val actions = ExportActions(
        onSelect = { viewModel.onIntent(ExportIntent.Select(it)) },
        onShare = { viewModel.onIntent(ExportIntent.Share) },
        onSave = { if (state.multi) folder.launch(null) else create.launch(state.fileName) },
    )
    val onDismiss = { viewModel.onIntent(ExportIntent.Dismiss) }
    SplatTheme(forceDark = forceDark, systemBars = false) {
        if (LocalConfiguration.current.screenWidthDp >= WIDE_DP) {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(dismissOnClickOutside = !state.working),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(max = DialogMaxWidth),
                ) {
                    ExportContent(state, actions, Modifier.padding(top = SplatDimens.SpacingLarge))
                }
            }
        } else {
            ExportBottomSheet(state.working, onDismiss) {
                ExportContent(state, actions, Modifier.navigationBarsPadding())
            }
        }
    }
}

private class ExportActions(
    val onSelect: (ExportFormat) -> Unit,
    val onShare: () -> Unit,
    val onSave: () -> Unit,
)

/** The phone sheet: it cannot be dragged closed while writing; Back then dismisses, which cancels the write. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportBottomSheet(working: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val writing by rememberUpdatedState(working)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !writing },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) { content() }
}

@Composable
private fun ExportContent(state: ExportUiState, actions: ExportActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SplatDimens.SpacingLarge)
            .padding(bottom = SplatDimens.SpacingLarge),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
    ) {
        Text(
            text = if (state.multi) {
                pluralStringResource(R.plurals.exp_title_many, state.entries.size, state.entries.size)
            } else {
                stringResource(R.string.exp_title)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.selectableGroup()) {
            state.formats.forEach { format ->
                val selected = format == state.selected
                FormatRow(
                    format = format,
                    selected = selected,
                    enabled = !state.working,
                    size = size(state.size(format)),
                    lowStorage = if (selected && state.lowStorage) {
                        stringResource(
                            R.string.exp_low_storage,
                            size(state.size(format) + ExportStorage.HEADROOM),
                            size(state.freeBytes ?: 0),
                        )
                    } else {
                        null
                    },
                    onClick = { actions.onSelect(format) },
                )
            }
        }
        if (state.status is ExportStatus.Failed) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    text = stringResource(R.string.exp_error),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(SplatDimens.SpacingMedium),
                )
            }
        }
        val status = state.status
        if (status is ExportStatus.Working) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.exp_writing), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
                Button(onClick = actions.onShare, enabled = state.canExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.IosShare, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.exp_share))
                }
                FilledTonalButton(onClick = actions.onSave, enabled = state.canExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(if (state.multi) R.string.exp_save_folder else R.string.exp_save))
                }
            }
        }
    }
}

/** One format: the whole row is the radio button and reads its name, description and size. */
@Composable
private fun FormatRow(
    format: ExportFormat,
    selected: Boolean,
    enabled: Boolean,
    size: String,
    lowStorage: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = SplatDimens.SpacingSmall),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = SplatDimens.SpacingMedium)) {
            Text(format.title(), style = MaterialTheme.typography.bodyLarge)
            format.body()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.exp_size, size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lowStorage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ExportFormat.title(): String = when (this) {
    ExportFormat.PLY -> stringResource(R.string.exp_ply)
    ExportFormat.SPLAT -> stringResource(R.string.exp_splat)
    ExportFormat.SPZ -> label
}

@Composable
private fun ExportFormat.body(): String? = when (this) {
    ExportFormat.PLY -> stringResource(R.string.exp_ply_body)
    ExportFormat.SPLAT -> stringResource(R.string.exp_splat_body)
    ExportFormat.SPZ -> null
}
