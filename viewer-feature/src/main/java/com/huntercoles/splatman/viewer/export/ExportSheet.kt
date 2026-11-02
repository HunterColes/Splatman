package com.huntercoles.splatman.viewer.export

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.viewer.R
import timber.log.Timber

/**
 * The viewer's export sheet: format choice (PLY by default; SPZ only once it has a codec), Save through the system
 * `CreateDocument` picker, Share through `ACTION_SEND` with a FileProvider URI, and progress and error states.
 */
@Composable
fun ExportSheet(viewModel: ExportViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportCodecs.MIME)) { uri ->
        viewModel.onIntent(ExportIntent.SaveTo(uri?.toString()))
    }

    LaunchedEffect(state.share) {
        val share = state.share ?: return@LaunchedEffect
        try {
            context.startActivity(AndroidExportTarget.shareIntent(share.uri, share.fileName))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app to share %s", share.fileName)
        }
        viewModel.onIntent(ExportIntent.ShareLaunched)
    }

    if (!state.sheetOpen) return
    ModalBottomSheet(onDismissRequest = { viewModel.onIntent(ExportIntent.Dismiss) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = SplatDimens.SpacingLarge)
                .padding(bottom = SplatDimens.SpacingLarge),
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            state.formats.forEach { format ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = format == state.selected,
                        onClick = { viewModel.onIntent(ExportIntent.Select(format)) },
                        enabled = state.status !is ExportStatus.Working,
                    )
                    Text(format.label)
                }
            }
            Text(
                text = state.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = state.status) {
                ExportStatus.Idle -> Unit
                is ExportStatus.Working -> LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                is ExportStatus.Saved -> Text(stringResource(R.string.export_saved, status.fileName))
                is ExportStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
                Button(onClick = { create.launch(state.fileName) }, enabled = state.canExport) {
                    Text(stringResource(R.string.export_save))
                }
                OutlinedButton(onClick = { viewModel.onIntent(ExportIntent.Share) }, enabled = state.canExport) {
                    Text(stringResource(R.string.export_share))
                }
            }
        }
    }
}
