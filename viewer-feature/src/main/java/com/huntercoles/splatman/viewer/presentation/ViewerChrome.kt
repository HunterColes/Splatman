package com.huntercoles.splatman.viewer.presentation

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled._360
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.viewer.R
import java.text.NumberFormat
import java.util.Date

/**
 * The top bar on a top-down scrim: Back, the name (tap to rename), Details and the overflow menu (Rename, Revert crop
 * while an original is kept, Delete, Open file).
 */
@Composable
@Suppress("LongParameterList")
internal fun ViewerTopBar(
    title: String,
    canEdit: Boolean,
    hasDetails: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
    canRevert: Boolean = false,
    onRevert: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(SplatColors.OverlayScrim, Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = SplatDimens.SpacingXSmall, vertical = SplatDimens.SpacingXSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.view_back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (canEdit) Modifier.clickable(onClick = onRename) else Modifier)
                .padding(horizontal = SplatDimens.SpacingSmall),
        )
        if (hasDetails) {
            IconButton(onClick = onDetails) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.view_info))
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.view_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_rename)) },
                        onClick = { menu = false; onRename() },
                    )
                }
                if (canRevert) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.crop_revert)) },
                        onClick = { menu = false; onRevert() },
                    )
                }
                if (canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_delete)) },
                        onClick = { menu = false; onDelete() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.view_open_file)) },
                    onClick = { menu = false; onOpenFile() },
                )
            }
        }
    }
}

/** The bottom bar on a bottom-up scrim: Reset view, Auto-rotate, Crop, Stats, and Share. All fit at phone width. */
@Composable
@Suppress("LongParameterList")
internal fun ViewerBottomBar(
    autoRotate: Boolean,
    showStats: Boolean,
    onReset: () -> Unit,
    onAutoRotate: () -> Unit,
    onStats: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    canCrop: Boolean = false,
    onCrop: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, SplatColors.OverlayScrim)))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(SplatDimens.SpacingDefault),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
    ) {
        IconButton(onClick = onReset) {
            Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.view_reset))
        }
        FilledIconToggleButton(checked = autoRotate, onCheckedChange = { onAutoRotate() }) {
            Icon(Icons.Default._360, contentDescription = stringResource(R.string.view_autorotate))
        }
        IconButton(onClick = onCrop, enabled = canCrop) {
            Icon(Icons.Rounded.Crop, contentDescription = stringResource(R.string.crop_action))
        }
        IconToggleButton(checked = showStats, onCheckedChange = { onStats() }) {
            Icon(Icons.Default.Speed, contentDescription = stringResource(R.string.view_stats))
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onShare) {
            Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.view_share))
        }
    }
}

/**
 * The one-time gesture hint: three rows and "Got it". [room]: the inside-the-room wording (look around, slide, step
 * in or back) with the same icons, so both variants have the same height.
 */
@Composable
internal fun GestureHint(onDismiss: () -> Unit, modifier: Modifier = Modifier, room: Boolean = false) {
    Card(
        modifier = modifier.padding(horizontal = SplatDimens.SpacingDefault),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(SplatDimens.SpacingDefault)) {
            if (room) {
                HintRow(Icons.Default.TouchApp, stringResource(R.string.view_hint_room_look))
                HintRow(Icons.Default.OpenWith, stringResource(R.string.view_hint_room_move))
                HintRow(Icons.Default.ZoomIn, stringResource(R.string.view_hint_room_walk))
            } else {
                HintRow(Icons.Default.TouchApp, stringResource(R.string.view_hint_spin))
                HintRow(Icons.Default.OpenWith, stringResource(R.string.view_hint_move))
                HintRow(Icons.Default.ZoomIn, stringResource(R.string.view_hint_zoom))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.view_hint_ok))
            }
        }
    }
}

@Composable
private fun HintRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = SplatDimens.SpacingXSmall),
    ) {
        Icon(icon, contentDescription = null)
        Text(text, modifier = Modifier.padding(start = SplatDimens.SpacingDefault))
    }
}

/** The details sheet: name, date, splats, size, how it was made, the format and "Room scan"; selectable text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsSheet(details: ViewerDetails, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SplatDimens.CornerLarge, topEnd = SplatDimens.CornerLarge),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(SplatDimens.SpacingLarge),
                verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
            ) {
                Text(details.name, style = MaterialTheme.typography.titleLarge)
                details.createdAtMs?.let {
                    Text(DateFormat.getMediumDateFormat(context).format(Date(it)))
                }
                Text(stringResource(R.string.view_info_splats, NumberFormat.getIntegerInstance().format(details.splats)))
                details.sizeBytes?.let {
                    Text(stringResource(R.string.view_info_size, Formatter.formatShortFileSize(context, it)))
                }
                val format = details.format.name
                if (details.frames != null) {
                    Text(stringResource(R.string.view_info_frames, details.frames))
                    Text(stringResource(R.string.view_info_format, format))
                } else {
                    Text(stringResource(R.string.view_info_imported, format))
                }
                if (details.room) {
                    Text(stringResource(R.string.view_info_room))
                }
            }
        }
    }
}

/** Rename: the name prefilled and selected; Save disabled when blank; a counter past 60 characters. */
@Composable
internal fun RenameScanDialog(
    dialog: RenameDialog,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(dialog.text, TextRange(0, dialog.text.length))) }
    if (field.text != dialog.text) field = field.copy(text = dialog.text, selection = TextRange(dialog.text.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.view_rename)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    val clipped = RenameRules.clip(it.text)
                    field = it.copy(text = clipped)
                    onChange(clipped)
                },
                singleLine = true,
                supportingText = if (dialog.showCounter) {
                    { Text("${dialog.text.length} / ${RenameRules.MAX_LENGTH}") }
                } else {
                    null
                },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = dialog.canSave) { Text(stringResource(R.string.view_rename_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.view_cancel)) }
        },
    )
}

/** Revert crop confirm (docs/design/screens/crop.md): Cancel and Revert (`primary`). */
@Composable
internal fun RevertCropDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.crop_revert_title)) },
        text = { Text(stringResource(R.string.crop_revert_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.crop_revert_confirm), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.crop_cancel)) }
        },
    )
}

/** Delete confirm: Cancel and Delete (error colour). */
@Composable
internal fun DeleteScanDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.view_delete_title)) },
        text = { Text(stringResource(R.string.view_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.view_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.view_cancel)) }
        },
    )
}
