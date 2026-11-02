package com.huntercoles.splatman.capture.presentation.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.preview.CameraPreview
import com.huntercoles.splatman.capture.preview.PreviewTarget
import com.huntercoles.splatman.capture.tracking.arcore.ArCoreAvailability
import com.huntercoles.splatman.capture.tracking.rememberCameraPermissionRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the UI thread waits for the tracker to stop drawing into a surface that is being destroyed. */
private const val PREVIEW_DETACH_TIMEOUT_MS = 1_000L

/**
 * The capture route: live ARCore camera full screen with a tracking hint, or the reason it cannot run (unsupported,
 * install needed, permission, failure). Portrait-locked while shown. Plain Material 3; #4's design restyles it.
 */
@Composable
fun CaptureScreen(onBack: () -> Unit, viewModel: CaptureViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val availability = remember(context) { ArCoreAvailability(context) }
    val currentState by rememberUpdatedState(state)

    LockPortrait(activity)

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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        CaptureContent(state, viewModel, requestPermission, onInstall = {
            val answer = activity?.let { availability.requestInstall(it, userRequested = true) }
            if (answer != null) viewModel.onIntent(CaptureIntent.InstallResult(answer))
        }, onOpenSettings = { context.openAppSettings() }, onBack = onBack)
    }
}

@Composable
private fun CaptureContent(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    requestPermission: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            is CapturePhase.Preview -> {
                CameraPreview(
                    onTarget = { target -> attachPreview(viewModel, target) },
                    modifier = Modifier.fillMaxSize(),
                )
                TrackingHintChip(
                    hint = phase.hint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp),
                )
            }
            CapturePhase.Checking -> Message(R.string.capture_checking) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }
            CapturePhase.Unsupported -> Message(R.string.capture_unsupported)
            CapturePhase.InstallNeeded -> Message(R.string.capture_install_needed) {
                Button(onClick = onInstall, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.capture_install))
                }
            }
            CapturePhase.PermissionNeeded -> {
                LaunchedEffect(Unit) { requestPermission() }
                Message(R.string.capture_permission_needed)
            }
            CapturePhase.PermissionDenied -> Message(R.string.capture_permission_denied) {
                Button(onClick = requestPermission, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.capture_permission_allow))
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.capture_permission_settings))
                }
            }
            is CapturePhase.Failed -> Message(R.string.capture_failed, phase.reason) {
                Button(
                    onClick = { viewModel.onIntent(CaptureIntent.Retry) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.capture_retry)) }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.capture_back))
        }
    }
}

/** Hands the surface to the tracker; on destroy, waits (bounded) until the tracker stopped drawing into it. */
private fun attachPreview(viewModel: CaptureViewModel, target: PreviewTarget?) {
    val job = viewModel.setPreview(target)
    if (target == null) runBlocking { withTimeoutOrNull(PREVIEW_DETACH_TIMEOUT_MS) { job.join() } }
}

@Composable
private fun TrackingHintChip(hint: TrackingHint, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = stringResource(hint.label()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@StringRes
private fun TrackingHint.label(): Int = when (this) {
    TrackingHint.TRACKING -> R.string.capture_hint_tracking
    TrackingHint.LOOKING_FOR_SURFACES -> R.string.capture_hint_looking
    TrackingHint.MOVE_SLOWER -> R.string.capture_hint_move_slower
    TrackingHint.MORE_LIGHT -> R.string.capture_hint_more_light
    TrackingHint.MORE_DETAIL -> R.string.capture_hint_more_detail
    TrackingHint.CAMERA_IN_USE -> R.string.capture_hint_camera_in_use
    TrackingHint.TRACKING_LOST -> R.string.capture_hint_lost
}

@Composable
private fun Message(
    @StringRes text: Int,
    vararg args: Any,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(text, *args),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        actions()
    }
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
