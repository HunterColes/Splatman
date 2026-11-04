package com.huntercoles.splatman.library.presentation.folders

import android.text.BidiFormatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.library.R

/** From this window width on the Move sheet is a dialog (docs/design/screens/library.md). */
const val MOVE_DIALOG_FROM_DP = 600

/** A Move row's test tag: `move_row_<folderId>`, `move_row_root` for the Library, `move_row_new` for New folder. */
fun moveRowTag(folderId: String?): String = "move_row_${folderId ?: "root"}"

const val MOVE_ROW_NEW_TAG = "move_row_new"

private const val DISABLED_ALPHA = 0.38f
private val ROW_MIN_HEIGHT = 56.dp

/**
 * "Move n scans to": a pinned title, then New folder, the Library and every folder of [sheet] (the ViewModel orders
 * them). The place where every selected scan already is stays disabled and says Here now. A [ModalBottomSheet] on a
 * phone, a dialog from [MOVE_DIALOG_FROM_DP] on. Stateless: [onNewFolder], [onMoveTo] (null = the Library) and
 * [onDismiss] go to the ViewModel.
 */
@Composable
fun MoveToSheet(
    sheet: MoveSheet,
    onNewFolder: () -> Unit,
    onMoveTo: (folderId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (LocalConfiguration.current.screenWidthDp >= MOVE_DIALOG_FROM_DP) {
        BasicAlertDialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                MoveContent(sheet, onNewFolder, onMoveTo, Modifier.padding(vertical = 24.dp))
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MoveContent(sheet, onNewFolder, onMoveTo, Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun MoveContent(
    sheet: MoveSheet,
    onNewFolder: () -> Unit,
    onMoveTo: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = sheet.scanIds.size
    Column(modifier) {
        Text(
            text = pluralStringResource(R.plurals.lib_move_title, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyColumn {
            item(key = MOVE_ROW_NEW_TAG) {
                MoveRow(
                    icon = Icons.Rounded.CreateNewFolder,
                    label = stringResource(R.string.lib_folder_new),
                    trailing = null,
                    enabled = true,
                    tag = MOVE_ROW_NEW_TAG,
                    onClick = onNewFolder,
                )
            }
            items(sheet.targets, key = { moveRowTag(it.folderId) }) { target ->
                val label = target.name ?: stringResource(R.string.lib_move_root)
                val trailing = when {
                    target.hereNow -> stringResource(R.string.lib_move_here_now)
                    target.folderId == null -> null
                    target.scanCount == 0 -> stringResource(R.string.lib_folder_count_empty)
                    else -> pluralStringResource(R.plurals.lib_folder_count, target.scanCount, target.scanCount)
                }
                MoveRow(
                    icon = if (target.folderId == null) Icons.Rounded.GridView else Icons.Rounded.Folder,
                    label = label,
                    trailing = trailing,
                    enabled = !target.hereNow,
                    tag = moveRowTag(target.folderId),
                    onClick = { onMoveTo(target.folderId) },
                )
            }
        }
    }
}

@Composable
private fun MoveRow(
    icon: ImageVector,
    label: String,
    trailing: String?,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val spoken = if (trailing == null) label else "${BidiFormatter.getInstance().unicodeWrap(label)}, $trailing"
    ListItem(
        headlineContent = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing?.let { { Text(it, maxLines = 1) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .testTag(tag),
    )
}
