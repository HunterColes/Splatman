package com.huntercoles.splatman.capture.presentation.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.preview.CameraPreview
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.CameraPermission
import com.huntercoles.splatman.capture.tracking.FlavourTracking
import com.huntercoles.splatman.capture.tracking.rememberCameraPermissionRequest
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the UI thread waits for the tracker to stop drawing into a surface that is being destroyed. */
private const val PREVIEW_DETACH_TIMEOUT_MS = 1_000L

/**
 * The capture route (docs/design/screens/capture.md): live ARCore camera full screen with the guidance overlay
 * ([CaptureOverlay]), or the reason it cannot run (permission, unsupported, install needed, failure). Always dark and
 * portrait-locked while shown. Close and system Back go through the view model (it asks before discarding frames);
 * a saved scan goes to [onScanSaved] (its id), which opens Processing.
 */
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onScanSaved: (String) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnScanSaved by rememberUpdatedState(onScanSaved)
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.openProcessing.collect { currentOnScanSaved(it) } }
    LaunchedEffect(viewModel) { viewModel.leave.collect { currentOnBack() } }
    val view = LocalView.current
    LaunchedEffect(viewModel) { viewModel.haptics.collect { view.play(it) } }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val availability = remember(context) { FlavourTracking.support(context) }
    val currentState by rememberUpdatedState(state)

    LockPortrait(activity)
    BackHandler { viewModel.onIntent(CaptureIntent.Close) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onIntent(CaptureIntent.Foreground(true))
                    if (currentState.installPending && activity != null) {
                        val answer = availability.requestInstall(activity, userRequested = false)
                        viewModel.onIntent(CaptureIntent.InstallResult(answer))
                    }
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.onIntent(CaptureIntent.Foreground(false))
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Leaving the screen pauses the session even before the view model is cleared.
            viewModel.onIntent(CaptureIntent.Foreground(false))
        }
    }

    val requestPermission = rememberCameraPermissionRequest { granted ->
        viewModel.onIntent(CaptureIntent.PermissionResult(granted))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = SplatColors.ViewerBackground) {
        CaptureContent(
            state = state,
            viewModel = viewModel,
            requestPermission = requestPermission,
            permanentlyDenied = {
                activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, CameraPermission.NAME)
            },
            onInstall = {
                val answer = activity?.let { availability.requestInstall(it, userRequested = true) }
                if (answer != null) viewModel.onIntent(CaptureIntent.InstallResult(answer))
            },
            onOpenSettings = { context.openAppSettings() },
            onBack = { viewModel.onIntent(CaptureIntent.Close) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureContent(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    requestPermission: () -> Unit,
    permanentlyDenied: () -> Boolean,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var help by rememberSaveable { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    Box(modifier = Modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            is CapturePhase.Preview -> {
                CameraPreview(
                    onTarget = { target -> attachPreview(viewModel, target) },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewModel.onIntent(CaptureIntent.ViewSize(it.width, it.height)) }
                        .pointerInput(viewModel) {
                            detectTapGestures { viewModel.onIntent(CaptureIntent.Tap(it.x, it.y)) }
                        },
                )
                CaptureOverlay(state, fontScale, viewModel::onIntent, onHelp = { help = true })
            }
            CapturePhase.Checking -> Message(R.string.cap_starting, onBack = onBack) {
                CircularProgressIndicator(modifier = Modifier.padding(top = SplatDimens.SpacingDefault))
            }
            is CapturePhase.Unsupported -> Message(
                R.string.ar_unsupported_body,
                title = R.string.ar_unsupported_title,
                details = phase.reason,
                onBack = onBack,
            )
            is CapturePhase.InstallNeeded -> Message(
                R.string.ar_install_body,
                title = R.string.ar_install_title,
                details = phase.reason,
                onBack = onBack,
            ) {
                Button(onClick = onInstall, modifier = Modifier.padding(top = SplatDimens.SpacingDefault)) {
                    Text(stringResource(R.string.ar_install_action))
                }
            }
            is CapturePhase.CouldntCheck -> Message(
                R.string.ar_check_failed_body,
                title = R.string.ar_check_failed_title,
                details = phase.reason,
                onBack = onBack,
            ) {
                Button(
                    onClick = { viewModel.onIntent(CaptureIntent.Retry) },
                    modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
                ) { Text(stringResource(R.string.capture_retry)) }
            }
            CapturePhase.PermissionNeeded -> {
                LaunchedEffect(Unit) { requestPermission() }
                PermissionMessage(permanent = false, requestPermission, onOpenSettings, onBack)
            }
            CapturePhase.PermissionDenied ->
                PermissionMessage(permanent = permanentlyDenied(), requestPermission, onOpenSettings, onBack)
            is CapturePhase.Failed -> Message(R.string.capture_failed, phase.reason, onBack = onBack) {
                Button(
                    onClick = { viewModel.onIntent(CaptureIntent.Retry) },
                    modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
                ) { Text(stringResource(R.string.capture_retry)) }
            }
        }
    }
    if (help) {
        ModalBottomSheet(onDismissRequest = { help = false }) { Tips() }
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(CaptureIntent.KeepScanning) },
            title = { Text(stringResource(R.string.cap_discard_title)) },
            text = { Text(stringResource(R.string.cap_discard_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.onIntent(CaptureIntent.Discard) }) {
                    Text(stringResource(R.string.cap_discard_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(CaptureIntent.KeepScanning) }) {
                    Text(stringResource(R.string.cap_keep_scanning))
                }
            },
        )
    }
}

/** The help sheet: the three onboarding tips. */
@Composable
private fun Tips() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = SplatDimens.SpacingLarge, vertical = SplatDimens.SpacingDefault),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault),
    ) {
        Text(stringResource(R.string.onb_tips_title), style = MaterialTheme.typography.titleLarge)
        for (tip in listOf(R.string.onb_tip_slow, R.string.onb_tip_around, R.string.onb_tip_light)) {
            Text(stringResource(tip), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PermissionMessage(
    permanent: Boolean,
    requestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Message(R.string.cap_permission_body, title = R.string.cap_permission_title, icon = true, onBack = onBack) {
        Button(
            onClick = if (permanent) onOpenSettings else requestPermission,
            modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
        ) {
            Text(stringResource(if (permanent) R.string.cap_permission_settings else R.string.cap_permission_allow))
        }
    }
}

/** Hands the surface to the tracker; on destroy, waits (bounded) until the tracker stopped drawing into it. */
private fun attachPreview(viewModel: CaptureViewModel, target: PreviewTarget?) {
    val job = viewModel.setPreview(target)
    if (target == null) runBlocking { withTimeoutOrNull(PREVIEW_DETACH_TIMEOUT_MS) { job.join() } }
}

/** A centred message on the dark screen, an optional [details] line (the raw reason), its [actions], then Back. */
@Composable
private fun Message(
    @StringRes text: Int,
    vararg args: Any,
    @StringRes title: Int? = null,
    icon: Boolean = false,
    details: String? = null,
    onBack: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val onScrim = SplatColors.OverlayOnScrim
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(SplatDimens.SpacingXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon) {
            Icon(Icons.Rounded.VideocamOff, contentDescription = null, tint = onScrim, modifier = Modifier.size(48.dp))
        }
        if (title != null) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.headlineSmall,
                color = onScrim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
            )
        }
        Text(
            text = stringResource(text, *args),
            style = MaterialTheme.typography.bodyLarge,
            color = onScrim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SplatDimens.SpacingSmall),
        )
        if (!details.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.ar_details, details),
                style = MaterialTheme.typography.bodySmall,
                color = onScrim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = SplatDimens.SpacingSmall),
            )
        }
        actions()
        TextButton(onClick = onBack) { Text(stringResource(R.string.capture_back)) }
    }
}

/** CONFIRM or REJECT on API 30+, else a long press (docs/design/tokens.md, Haptics). */
private fun View.play(h: CaptureHaptic) {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        when (h) {
            CaptureHaptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
            CaptureHaptic.REJECT -> HapticFeedbackConstants.REJECT
        }
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

/** Portrait while the capture screen is shown (the tracker and preview assume it); restored when it leaves. */
@Composable
private fun LockPortrait(activity: Activity?) {
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity != null && previous != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = previous
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
