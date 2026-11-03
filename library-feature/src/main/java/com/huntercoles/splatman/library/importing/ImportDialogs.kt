package com.huntercoles.splatman.library.importing

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.R
import java.text.NumberFormat

/** The import flow's dialog over the Library (docs/design/screens/import.md): validating, or the error. */
@Composable
fun ImportDialogHost(dialog: ImportDialog?, onIntent: (ImportIntent) -> Unit) {
    when (dialog) {
        null -> Unit
        is ImportDialog.Validating -> ValidatingDialog(dialog.fileName) { onIntent(ImportIntent.Cancel) }
        is ImportDialog.Error -> ErrorDialog(dialog.message) { onIntent(ImportIntent.Dismiss) }
    }
}

/** Modal: a tap outside does nothing, Back cancels. Announced as it appears. */
@Composable
private fun ValidatingDialog(fileName: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault)) {
                Text(
                    stringResource(R.string.imp_validating, fileName),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun ErrorDialog(message: ImportMessage, onOk: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
        text = {
            Text(
                message.format(context),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.imp_ok)) } },
    )
}

/** The message with its counts and sizes formatted for the user's locale. */
fun ImportMessage.format(context: Context): String {
    val formatted = args.map { arg ->
        when (arg) {
            is MessageArg.Text -> arg.value
            is MessageArg.Count -> NumberFormat.getIntegerInstance().format(arg.value)
            is MessageArg.Bytes -> Formatter.formatShortFileSize(context, arg.value)
        }
    }
    return context.getString(text, *formatted.toTypedArray())
}
