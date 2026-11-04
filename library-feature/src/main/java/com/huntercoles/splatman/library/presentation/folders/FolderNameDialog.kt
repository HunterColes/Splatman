package com.huntercoles.splatman.library.presentation.folders

import android.text.BidiFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.huntercoles.splatman.core.design.SplatDimens
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.domain.repository.FolderRepository

/** The name field's test tag. */
const val FOLDER_NAME_FIELD_TAG = "folder_name_field"

/** The counter shows from this many characters on (docs/design/screens/library.md). */
const val FOLDER_NAME_COUNTER_FROM = 50

/**
 * New folder or Rename folder ([dialog]'s mode) as a dialog: one field labelled Name, focused with the keyboard up;
 * Rename starts with the whole name selected. Confirm is disabled while the trimmed name is blank; typing stops at
 * [FolderRepository.MAX_NAME_LENGTH] characters and the counter shows from [FOLDER_NAME_COUNTER_FROM]. A taken name
 * shows as the field's error. The name reads in its own direction. Stateless: [onTextChange], [onConfirm] and
 * [onDismiss] go to the ViewModel.
 */
@Composable
fun FolderNameDialog(
    dialog: NameDialog,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        FolderNameCard(dialog, onTextChange, onConfirm, onDismiss)
    }
}

/**
 * The name dialog's card, laid out as a Material 3 AlertDialog (title, field, Cancel and confirm at the end). Split
 * from its window because Robolectric never goes idle with a text field inside a dialog window, so the tests and
 * goldens draw this card over a scrim.
 */
@Composable
internal fun FolderNameCard(
    dialog: NameDialog,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The selection lives here, the text is the state's. Rename starts with all of it selected, else the cursor ends it.
    val rename = dialog.mode is NameDialogMode.Rename
    var field by remember {
        val start = if (rename) 0 else dialog.text.length
        mutableStateOf(TextFieldValue(dialog.text, TextRange(start, dialog.text.length)))
    }
    if (field.text != dialog.text) field = TextFieldValue(dialog.text, TextRange(dialog.text.length))
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val length = dialog.text.codePointCount(0, dialog.text.length)
    val taken = dialog.error == NameError.Taken
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 560.dp),
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(SplatDimens.SpacingLarge)) {
            Text(
                text = stringResource(if (rename) R.string.lib_folder_rename else R.string.lib_folder_new),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Spacer(Modifier.height(SplatDimens.SpacingDefault))
            OutlinedTextField(
                value = field,
                onValueChange = {
                    val capped = it.copy(text = capName(it.text))
                    field = capped
                    if (capped.text != dialog.text) onTextChange(capped.text)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(FOLDER_NAME_FIELD_TAG),
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                label = { Text(stringResource(R.string.lib_name)) },
                supportingText = if (taken || length >= FOLDER_NAME_COUNTER_FROM) {
                    { NameSupport(taken = taken, name = dialog.text.trim(), length = length) }
                } else {
                    null
                },
                isError = taken || dialog.error == NameError.TooLong,
                enabled = !dialog.saving,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (dialog.canConfirm) onConfirm() }),
                singleLine = true,
            )
            Spacer(Modifier.height(SplatDimens.SpacingLarge))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) }
                TextButton(onClick = onConfirm, enabled = dialog.canConfirm) {
                    Text(stringResource(if (rename) R.string.lib_rename else R.string.lib_folder_create))
                }
            }
        }
    }
}

/** The field's supporting line: the taken-name error at the start, the counter at the end from 50 characters on. */
@Composable
private fun NameSupport(taken: Boolean, name: String, length: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault)) {
        Text(
            text = if (taken) {
                stringResource(R.string.lib_folder_name_taken, BidiFormatter.getInstance().unicodeWrap(name))
            } else {
                ""
            },
            modifier = Modifier.weight(1f),
        )
        if (length >= FOLDER_NAME_COUNTER_FROM) {
            Text(text = stringResource(R.string.lib_folder_name_count, length), textAlign = TextAlign.End)
        }
    }
}

/** [text] cut to its first [FolderRepository.MAX_NAME_LENGTH] code points, so an emoji is never split. */
internal fun capName(text: String): String {
    val max = FolderRepository.MAX_NAME_LENGTH
    if (text.codePointCount(0, text.length) <= max) return text
    return text.substring(0, text.offsetByCodePoints(0, max))
}
