package com.huntercoles.splatman.library.presentation.folders

import android.text.BidiFormatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.huntercoles.splatman.library.R

/**
 * "Delete the folder [DeleteConfirm.name]?": the body says where its scans go (or that it is empty), and the confirm
 * button is in the error colour. Stateless: [onConfirm] and [onDismiss] go to the ViewModel.
 */
@Composable
fun DeleteFolderDialog(confirm: DeleteConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.lib_folder_delete_title, BidiFormatter.getInstance().unicodeWrap(confirm.name)))
        },
        text = {
            Text(
                if (confirm.scanCount == 0) {
                    stringResource(R.string.lib_folder_delete_body_empty)
                } else {
                    pluralStringResource(R.plurals.lib_folder_delete_body, confirm.scanCount, confirm.scanCount)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.lib_folder_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}
