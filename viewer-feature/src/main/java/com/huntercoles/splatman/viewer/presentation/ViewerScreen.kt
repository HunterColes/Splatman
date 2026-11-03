package com.huntercoles.splatman.viewer.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.splatDuration
import com.huntercoles.splatman.viewer.R
import com.huntercoles.splatman.viewer.export.ExportIntent
import com.huntercoles.splatman.viewer.export.ExportSheet
import com.huntercoles.splatman.viewer.export.ExportViewModel
import com.huntercoles.splatman.viewer.presentation.crop.CropEffect
import com.huntercoles.splatman.viewer.presentation.crop.CropIntent
import com.huntercoles.splatman.viewer.presentation.crop.CropOverlay
import com.huntercoles.splatman.viewer.presentation.crop.CropUiState
import com.huntercoles.splatman.viewer.presentation.crop.CropViewModel
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.gl.RenderStats
import com.huntercoles.splatman.viewer.rendering.gl.SplatRenderer
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The splat viewer (docs/design/screens/viewer.md): a full-bleed GLES 3.0 [SplatRenderer] with the chrome floating
 * on scrims. One finger orbits, two pan and pinch, a double tap eases back to the initial view, a fling keeps
 * turning and a single tap hides the chrome (not while TalkBack is on). Every gesture also has a button: Reset and
 * Auto-rotate in the bottom bar, TalkBack custom actions on the render, and arrow, `+`/`-` and `0` keys.
 *
 * Crop mode ([CropViewModel], docs/design/screens/crop.md) replaces the bars with [CropOverlay]; an Apply or a Revert
 * reloads the source through Loading with the camera kept, then shows its snackbar.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = hiltViewModel(),
    exportViewModel: ExportViewModel = hiltViewModel(),
    cropViewModel: CropViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val crop by cropViewModel.uiState.collectAsStateWithLifecycle()
    val cropping = crop !is CropUiState.Idle
    val context = LocalContext.current
    val view = LocalView.current
    val camera = viewModel.camera.controller
    val renderer = remember { SplatRenderer(camera) }
    val stats by renderer.stats.collectAsStateWithLifecycle()
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }
    val talkBack = rememberTalkBackOn()
    val resetMs = splatDuration(SplatMotion.DurationLong)
    val fadeMs = splatDuration(SplatMotion.DurationShort)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(ViewerIntent.Open(SplatSource.Document(uri.toString())))
    }

    val load = state.load
    val loaded = (load as? ViewerLoad.Loaded)?.scene
    // A crop reload keeps the camera where it is; read now, before ReloadShown clears it.
    val keepPose = state.reloaded != null
    LaunchedEffect(loaded) {
        exportViewModel.setScene(loaded?.name, loaded?.cloud)
        if (loaded != null) viewModel.camera.frame(loaded, size, keepPose)
        renderer.setScene(loaded?.render)
    }

    var reloadReason by rememberSaveable { mutableStateOf(ViewerReload.CROPPED) }
    var confirmRevert by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel, cropViewModel) {
        viewModel.ready.collect { cropViewModel.onIntent(CropIntent.SourceReady(it)) }
    }
    LaunchedEffect(cropViewModel) {
        cropViewModel.effects.collect { effect ->
            when (effect) {
                CropEffect.ReloadSource -> viewModel.onIntent(ViewerIntent.Reload(reloadReason))
                CropEffect.NothingCut -> scope.launch { snackbar.showSnackbar(context.getString(R.string.crop_nothing)) }
                CropEffect.RevertFailed ->
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.crop_revert_error)) }
            }
        }
    }
    val reloaded = state.reloaded
    LaunchedEffect(reloaded, loaded) {
        if (reloaded == null || loaded == null) return@LaunchedEffect
        viewModel.onIntent(ViewerIntent.ReloadShown)
        val text = if (reloaded == ViewerReload.CROPPED) {
            if (viewModel.haptics) view.confirm()
            context.getString(R.string.crop_done, NumberFormat.getIntegerInstance().format(loaded.count))
        } else {
            context.getString(R.string.crop_reverted)
        }
        scope.launch { snackbar.showSnackbar(text) }
    }
    val importDone = state.importDone
    LaunchedEffect(importDone) {
        if (importDone == null) return@LaunchedEffect
        viewModel.onIntent(ViewerIntent.ImportShown)
        scope.launch { snackbar.showSnackbar(context.getString(R.string.imp_done, importDone)) }
    }
    val cropBox = when (val c = crop) {
        is CropUiState.Editing -> c.box
        is CropUiState.Applying -> c.box
        is CropUiState.Refused -> c.box
        is CropUiState.Idle -> null
    }
    LaunchedEffect(cropBox) { renderer.setCropBox(cropBox) }
    BackHandler(enabled = cropping) {
        if (crop !is CropUiState.Applying) cropViewModel.onIntent(CropIntent.Cancel)
    }
    LaunchedEffect(state.autoRotate) { synchronized(camera) { camera.autoRotate = state.autoRotate } }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    val chromeShown = (state.chromeVisible || talkBack || loaded == null) && !cropping
    ImmersiveWhileHidden(hidden = !chromeShown && !cropping)
    val reset = { synchronized(camera) { camera.resetToInitial(animated = resetMs > 0) } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplatColors.ViewerBackground)
            .onSizeChanged { size = it },
    ) {
        SplatSurface(renderer = renderer, modifier = Modifier.fillMaxSize())
        if (loaded != null) {
            GestureLayer(
                camera = camera,
                scene = loaded,
                title = state.title,
                cropping = cropping,
                onTap = {
                    viewModel.onIntent(ViewerIntent.DismissHint)
                    if (!talkBack && !cropping) viewModel.onIntent(ViewerIntent.ToggleChrome)
                },
                onDoubleTap = {
                    viewModel.onIntent(ViewerIntent.DismissHint)
                    reset()
                },
                onGesture = { viewModel.onIntent(ViewerIntent.DismissHint) },
                onReset = reset,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (load) {
            ViewerLoad.Idle -> CentreMessage(stringResource(R.string.view_idle))
            is ViewerLoad.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    text = if (load.reload == ViewerReload.REVERTED) {
                        stringResource(R.string.crop_reverting)
                    } else {
                        stringResource(R.string.view_loading, load.source.name)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
                )
            }
            is ViewerLoad.Failed -> OpenError(
                failure = load,
                onBack = onBack,
                onRetry = { viewModel.onIntent(ViewerIntent.Retry) },
                modifier = Modifier.align(Alignment.Center),
            )
            is ViewerLoad.Loaded -> Unit
        }

        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                ViewerTopBar(
                    title = state.title,
                    canEdit = state.canEdit,
                    hasDetails = state.details != null,
                    onBack = onBack,
                    onRename = { viewModel.onIntent(ViewerIntent.StartRename) },
                    onDetails = { viewModel.onIntent(ViewerIntent.ShowDetails) },
                    onDelete = { viewModel.onIntent(ViewerIntent.AskDelete) },
                    onOpenFile = { picker.launch(arrayOf("*/*")) },
                    canRevert = (crop as? CropUiState.Idle)?.canRevert == true,
                    onRevert = { confirmRevert = true },
                )
                val current = stats
                if (state.showStats && loaded != null && current != null) {
                    Text(
                        text = statsLine(current),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = SplatDimens.SpacingDefault)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(SplatDimens.SpacingXSmall),
                    )
                }
            }
        }

        if (loaded != null) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.CenterHorizontally))
                if (state.showHint && !cropping) {
                    GestureHint(onDismiss = { viewModel.onIntent(ViewerIntent.DismissHint) })
                }
                AnimatedVisibility(
                    visible = chromeShown,
                    enter = fadeIn(tween(fadeMs)),
                    exit = fadeOut(tween(fadeMs)),
                ) {
                    ViewerBottomBar(
                        autoRotate = state.autoRotate,
                        showStats = state.showStats,
                        onReset = reset,
                        onAutoRotate = { viewModel.onIntent(ViewerIntent.ToggleAutoRotate) },
                        onStats = { viewModel.onIntent(ViewerIntent.ToggleStats) },
                        onShare = { exportViewModel.onIntent(ExportIntent.Show) },
                        canCrop = (crop as? CropUiState.Idle)?.offered == true,
                        onCrop = {
                            viewModel.onIntent(ViewerIntent.EnterCrop)
                            cropViewModel.onIntent(CropIntent.Enter(loaded.cloud))
                        },
                    )
                }
            }
            if (cropping) {
                CropOverlay(
                    state = crop,
                    cloud = loaded.cloud,
                    camera = camera,
                    haptics = viewModel.haptics,
                    onIntent = cropViewModel::onIntent,
                    onApply = {
                        reloadReason = ViewerReload.CROPPED
                        cropViewModel.onIntent(CropIntent.Apply)
                    },
                )
            }
        }
    }
    if (confirmRevert) {
        RevertCropDialog(
            onConfirm = {
                confirmRevert = false
                reloadReason = ViewerReload.REVERTED
                cropViewModel.onIntent(CropIntent.Revert)
            },
            onCancel = { confirmRevert = false },
        )
    }

    val details = state.details
    if (state.showDetails && details != null) {
        DetailsSheet(details = details, onDismiss = { viewModel.onIntent(ViewerIntent.HideDetails) })
    }
    state.rename?.let { dialog ->
        RenameScanDialog(
            dialog = dialog,
            onChange = { viewModel.onIntent(ViewerIntent.RenameChanged(it)) },
            onSave = { viewModel.onIntent(ViewerIntent.SaveRename) },
            onCancel = { viewModel.onIntent(ViewerIntent.CancelRename) },
        )
    }
    if (state.confirmDelete) {
        DeleteScanDialog(
            onConfirm = { viewModel.onIntent(ViewerIntent.ConfirmDelete) },
            onCancel = { viewModel.onIntent(ViewerIntent.CancelDelete) },
        )
    }
    ExportSheet(exportViewModel, onSaved = { message -> scope.launch { snackbar.showSnackbar(message) } })
}

/**
 * The render's input and accessibility: drag to orbit (a fling keeps turning), two fingers to pan and pinch, tap
 * and double tap; TalkBack custom actions and arrow, `+`/`-`, `0` keys for the same moves.
 */
@Composable
private fun GestureLayer(
    camera: GaussianCameraController,
    scene: LoadedScene,
    title: String,
    cropping: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onGesture: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val gesture by rememberUpdatedState(onGesture)
    val focus = remember { FocusRequester() }
    LaunchedEffect(scene) { runCatching { focus.requestFocus() } }

    val description = if (cropping) {
        stringResource(R.string.crop_render_desc, title)
    } else {
        stringResource(R.string.view_a11y_render, title, NumberFormat.getIntegerInstance().format(scene.count))
    }
    val actions = listOf(
        stringResource(R.string.view_a11y_rotate_left) to { orbit(camera, -1, 0) },
        stringResource(R.string.view_a11y_rotate_right) to { orbit(camera, 1, 0) },
        stringResource(R.string.view_a11y_tilt_up) to { orbit(camera, 0, 1) },
        stringResource(R.string.view_a11y_tilt_down) to { orbit(camera, 0, -1) },
        stringResource(R.string.view_a11y_zoom_in) to { zoom(camera, 1) },
        stringResource(R.string.view_a11y_zoom_out) to { zoom(camera, -1) },
        stringResource(R.string.view_reset) to onReset,
    )

    Box(
        modifier = modifier
            .semantics {
                contentDescription = description
                customActions = actions.map { (label, run) ->
                    CustomAccessibilityAction(label) {
                        run()
                        true
                    }
                }
            }
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> orbit(camera, -1, 0)
                    Key.DirectionRight -> orbit(camera, 1, 0)
                    Key.DirectionUp -> orbit(camera, 0, 1)
                    Key.DirectionDown -> orbit(camera, 0, -1)
                    Key.Plus, Key.Equals, Key.NumPadAdd -> zoom(camera, 1)
                    Key.Minus, Key.NumPadSubtract -> zoom(camera, -1)
                    Key.Zero, Key.NumPad0 -> onReset()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .pointerInput(camera) {
                detectTapGestures(onTap = { tap() }, onDoubleTap = { doubleTap() })
            }
            .pointerInput(camera) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    synchronized(camera) { camera.stopMotion() }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moving = false
                    var multi = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) multi = true
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        if (!moving) {
                            travelled += pan
                            if (travelled.getDistance() > slop || (fingers >= 2 && abs(zoom - 1f) > PINCH_SLOP)) {
                                moving = true
                                gesture()
                            }
                        }
                        if (moving) {
                            synchronized(camera) {
                                if (fingers >= 2) {
                                    if (zoom > 0f) camera.onZoom(1f / zoom)
                                    camera.onPan(-pan.x * PAN_PER_PX, pan.y * PAN_PER_PX)
                                } else if (fingers == 1 && !multi) {
                                    camera.onRotate(pan.x * ORBIT_PER_PX, pan.y * ORBIT_PER_PX)
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            tracker.addPosition(it.uptimeMillis, it.position)
                        }
                    } while (event.changes.any { it.pressed })
                    if (moving && !multi) {
                        val v = tracker.calculateVelocity()
                        synchronized(camera) { camera.fling(v.x * ORBIT_PER_PX, v.y * ORBIT_PER_PX) }
                    }
                }
            },
    )
}

private fun orbit(camera: GaussianCameraController, yaw: Int, pitch: Int) {
    synchronized(camera) { camera.orbitStep(yaw, pitch) }
}

private fun zoom(camera: GaussianCameraController, steps: Int) {
    synchronized(camera) { camera.zoomStep(steps) }
}

@Composable
private fun OpenError(failure: ViewerLoad.Failed, onBack: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(SplatDimens.SpacingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(SplatDimens.SpacingXXLarge),
        )
        Text(
            text = stringResource(R.string.view_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
        )
        Text(
            text = stringResource(if (failure.outOfMemory) R.string.view_error_memory else R.string.view_error_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SplatDimens.SpacingSmall),
        )
        Text(
            text = failure.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SplatDimens.SpacingXSmall),
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = SplatDimens.SpacingDefault)) {
            Text(stringResource(R.string.view_error_back))
        }
        TextButton(onClick = onRetry) { Text(stringResource(R.string.view_retry)) }
    }
}

@Composable
private fun CentreMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(SplatDimens.SpacingLarge),
        )
    }
}

/** True while TalkBack (touch exploration) is on; the chrome then never hides. */
@Composable
private fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Hides the system bars (swipe to reveal) while [hidden]; shows them again on leaving. */
@Composable
private fun ImmersiveWhileHidden(hidden: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(hidden, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun android.view.View.confirm() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A GLSurfaceView (GLES 3.0, context kept across pause) driven by [renderer], paused and resumed with the screen. */
@Composable
private fun SplatSurface(renderer: SplatRenderer, modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(lifecycleOwner, surface) {
        val view = surface
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(GLES_VERSION)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                surface = this
            }
        },
        modifier = modifier,
    )
}

private fun statsLine(s: RenderStats): String = String.format(
    Locale.US,
    "%.0f fps  %.1f ms  scale %.1f  %,d splats  %d sorts",
    s.fps,
    s.frameMs,
    s.scale,
    s.splats,
    s.sorts,
)

private const val GLES_VERSION = 3

/** Pointer pixels to controller units: about half a turn across a phone screen, a screen of pan per width. */
private const val ORBIT_PER_PX = 0.03f
private const val PAN_PER_PX = 0.1f

/** A pinch counts as a gesture once the spread changes by this fraction. */
private const val PINCH_SLOP = 0.02f
