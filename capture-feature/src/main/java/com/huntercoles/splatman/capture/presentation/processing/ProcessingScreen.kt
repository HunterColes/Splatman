package com.huntercoles.splatman.capture.presentation.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.ProcessingState

/**
 * The Processing route: the splat being built for one scan, with a progress bar, Cancel, and Retry after a failure or
 * cancel. When the splat is ready it calls [onOpenViewer] with the scan id. Back leaves the run going in the
 * background (its notification stays). Plain Material 3 until the design cards restyle it.
 */
@Composable
fun ProcessingScreen(
    onBack: () -> Unit,
    onOpenViewer: (String) -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnOpenViewer by rememberUpdatedState(onOpenViewer)
    LaunchedEffect(viewModel) { viewModel.openViewer.collect { currentOnOpenViewer(it) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(state.title()),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            val fraction = state.fraction
            when {
                fraction != null -> LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.canCancel -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.detail()?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canCancel) {
                    OutlinedButton(onClick = { viewModel.onIntent(ProcessingIntent.Cancel) }) {
                        Text(stringResource(R.string.processing_cancel))
                    }
                }
                if (state.canRetry) {
                    Button(onClick = { viewModel.onIntent(ProcessingIntent.Retry) }) {
                        Text(stringResource(R.string.processing_retry))
                    }
                }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.capture_back))
        }
    }
}

private fun ProcessingUiState.title(): Int = when {
    missing -> R.string.processing_missing
    else -> when (state) {
        ProcessingState.Idle -> R.string.processing_starting
        is ProcessingState.Running -> R.string.processing_running
        is ProcessingState.Paused -> R.string.processing_paused
        is ProcessingState.Done -> R.string.processing_done
        ProcessingState.Cancelled -> R.string.processing_cancelled
        is ProcessingState.Failed -> R.string.processing_failed
    }
}

@Composable
private fun ProcessingUiState.detail(): String? = when (val s = state) {
    is ProcessingState.Running -> s.progress?.let {
        stringResource(R.string.processing_progress, it.iteration, it.totalIterations, it.splatCount)
    } ?: stringResource(R.string.processing_loading)
    is ProcessingState.Paused -> stringResource(R.string.processing_paused_detail)
    is ProcessingState.Failed -> if (missing) null else s.message
    else -> null
}
