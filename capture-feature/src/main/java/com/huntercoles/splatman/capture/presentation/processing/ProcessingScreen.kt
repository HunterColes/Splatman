package com.huntercoles.splatman.capture.presentation.processing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDialog
import com.huntercoles.splatman.core.design.SplatDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Width from which the preview sits left of the progress column. */
private val WideFrom = 600.dp
private val PreviewMaxWidth = 480.dp
private val BarHeight = 8.dp
private const val DIMMED = 0.4f
private const val ANNOUNCE_STEP = 25

/**
 * The Processing route (docs/design/screens/processing.md): the scan's run with its stage, step, overall bar and time
 * left, Cancel (confirmed), and the Done and Failed states. Back leaves the run going; the first time it asks for
 * notifications (API 33+) with `proc_leave` as the reason. [onOpenViewer] gets the scan id from View it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onBack: () -> Unit,
    onOpenViewer: (String) -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnOpenViewer by rememberUpdatedState(onOpenViewer)
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var askReason by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onIntent(ProcessingIntent.NotificationAsked)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { event ->
            when (event) {
                is ProcessingEvent.OpenViewer -> currentOnOpenViewer(event.scanId)
                is ProcessingEvent.Share -> context.startActivity(ProcessingViewModel.shareIntent(event))
                ProcessingEvent.Leave -> currentOnBack()
                ProcessingEvent.Haptic -> view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.LONG_PRESS
                    },
                )
                ProcessingEvent.AskNotifications -> {
                    val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needed) askReason = true else viewModel.onIntent(ProcessingIntent.NotificationAsked)
                }
            }
        }
    }
    BackHandler { viewModel.onIntent(ProcessingIntent.Leave) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.proc_title)) },
            navigationIcon = {
                IconButton(onClick = { viewModel.onIntent(ProcessingIntent.Leave) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.proc_back_cd),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= WideFrom
            val padding = Modifier.padding(SplatDimens.SpacingLarge)
            if (wide) {
                Row(
                    modifier = padding.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingLarge),
                ) {
                    Preview(state, Modifier.weight(1f).widthIn(max = PreviewMaxWidth))
                    Details(
                        state,
                        onCancel = { confirmCancel = true },
                        onIntent = viewModel::onIntent,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                Column(
                    modifier = padding.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingLarge),
                ) {
                    Preview(state, Modifier.fillMaxWidth())
                    Details(state, onCancel = { confirmCancel = true }, onIntent = viewModel::onIntent)
                }
            }
        }
    }

    if (confirmCancel && state.canCancel) {
        SplatDialog(onDismissRequest = { confirmCancel = false }) {
            Text(stringResource(R.string.proc_cancel_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.proc_cancel_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = SplatDimens.SpacingMedium),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.proc_cancel_keep)) }
                TextButton(onClick = {
                    confirmCancel = false
                    viewModel.onIntent(ProcessingIntent.Cancel)
                }) { Text(stringResource(R.string.proc_cancel_confirm)) }
            }
        }
    }
    if (askReason) {
        SplatDialog(onDismissRequest = {
            askReason = false
            viewModel.onIntent(ProcessingIntent.NotificationAsked)
        }) {
            Text(stringResource(R.string.proc_leave), style = MaterialTheme.typography.bodyLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    askReason = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.onIntent(ProcessingIntent.NotificationAsked)
                    }
                }) { Text(stringResource(R.string.proc_notify_ok)) }
            }
        }
    }
}

/** The preview square: the capture's last frame, blurred while building, dimmed with an error icon on failure. */
@Composable
private fun Preview(state: ProcessingUiState, modifier: Modifier) {
    val frame by rememberFrame(state.lastFrame)
    val shape = RoundedCornerShape(SplatDimens.CornerLarge)
    Box(
        modifier = modifier.aspectRatio(1f).clip(shape).background(SplatColors.ViewerBackground),
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { image ->
            val look = when {
                state.failed -> Modifier.fillMaxSize().blur(SplatDimens.SpacingSmall)
                state.done -> Modifier.fillMaxSize()
                else -> Modifier.fillMaxSize().blur(SplatDimens.SpacingLarge)
            }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (state.failed) DIMMED else 1f,
                modifier = look,
            )
        }
        if (state.failed || state.missing) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SplatDimens.SpacingLarge * 3),
            )
        }
    }
}

@Composable
private fun rememberFrame(file: File?): State<ImageBitmap?> {
    val frame = remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        frame.value = if (file == null) null else withContext(Dispatchers.IO) { decodeFrame(file) }
    }
    return frame
}

private fun decodeFrame(file: File): ImageBitmap? = runCatching {
    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()

@Composable
private fun Details(
    state: ProcessingUiState,
    onCancel: () -> Unit,
    onIntent: (ProcessingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium)) {
        when {
            state.missing -> Text(stringResource(R.string.proc_missing), style = MaterialTheme.typography.headlineSmall)
            state.done -> Done(onIntent)
            state.failed -> Failed(state, onIntent)
            else -> Running(state, onCancel)
        }
    }
}

@Composable
private fun Running(state: ProcessingUiState, onCancel: () -> Unit) {
    val progress = state.progress
    val stage = progress?.stage ?: ProcessingStage.PREPARE
    val stageText = stringResource(stage.label)
    val announced = progress?.let { (it.percent() / ANNOUNCE_STEP) * ANNOUNCE_STEP } ?: 0
    val announcement = "$stageText, ${stringResource(R.string.proc_percent, announced)}"
    // A live region announced at stage changes and every 25 %, not every tick.
    Column(
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        },
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
    ) {
        Crossfade(targetState = stage, label = "stage") { s ->
            Text(stringResource(s.label), style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            stringResource(R.string.proc_step, stage.step, ProcessingStage.COUNT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val barModifier = Modifier.weight(1f).height(BarHeight).clip(RoundedCornerShape(SplatDimens.CornerXSmall))
            if (progress == null) {
                LinearProgressIndicator(modifier = barModifier, strokeCap = StrokeCap.Butt)
            } else {
                LinearProgressIndicator(
                    progress = { progress.overall },
                    modifier = barModifier,
                    strokeCap = StrokeCap.Butt,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Text(
                    stringResource(R.string.proc_percent, progress.percent()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = SplatDimens.SpacingMedium),
                )
            }
        }
        Text(etaText(if (state.hot) Eta.Unknown else state.eta), style = MaterialTheme.typography.bodyMedium)
    }
    AnimatedContent(targetState = state.hot, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hint") { hot ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hot) Icons.Rounded.Thermostat else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (hot) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (hot) R.string.proc_hot else R.string.proc_leave),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SplatDimens.SpacingSmall),
            )
        }
    }
    val cancelLabel = stringResource(R.string.proc_cancel_cd)
    OutlinedButton(
        onClick = onCancel,
        enabled = state.canCancel,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = cancelLabel },
    ) { Text(stringResource(R.string.proc_cancel)) }
}

@Composable
private fun etaText(eta: Eta): String = when (eta) {
    Eta.Unknown -> stringResource(R.string.proc_eta_unknown)
    Eta.AlmostDone -> stringResource(R.string.proc_eta_almost)
    is Eta.Minutes -> stringResource(
        R.string.proc_eta,
        pluralStringResource(R.plurals.proc_eta_minutes, eta.minutes, eta.minutes),
    )
}

@Composable
private fun Done(onIntent: (ProcessingIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text(
            stringResource(R.string.proc_done),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = SplatDimens.SpacingSmall),
        )
    }
    Button(onClick = { onIntent(ProcessingIntent.Open) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.proc_open))
    }
    FilledTonalButton(onClick = { onIntent(ProcessingIntent.Share) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.proc_share))
    }
}

@Composable
private fun Failed(state: ProcessingUiState, onIntent: (ProcessingIntent) -> Unit) {
    val context = LocalContext.current
    val failure = state.state as? ProcessingState.Failed
    Text(stringResource(R.string.proc_failed_title), style = MaterialTheme.typography.headlineSmall)
    state.failureCopy?.let { copy ->
        val text = if (copy == R.string.proc_failed_storage) {
            stringResource(copy, Formatter.formatShortFileSize(context, failure?.needBytes ?: 0))
        } else {
            stringResource(copy)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.canRetry) {
        Button(onClick = { onIntent(ProcessingIntent.Retry) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.proc_retry))
        }
    }
    TextButton(onClick = { onIntent(ProcessingIntent.Delete) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.proc_delete), color = MaterialTheme.colorScheme.error)
    }
    if (failure != null) {
        var open by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = SplatDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.proc_details), modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (open) {
            SelectionContainer {
                Text(
                    failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
