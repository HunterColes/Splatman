package com.huntercoles.splatman.viewer.presentation

import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.viewer.R
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.gl.RenderStats
import com.huntercoles.splatman.viewer.rendering.gl.SplatRenderer
import java.util.Locale
import kotlin.math.max

/**
 * The splat viewer: a GLES 3.0 [SplatRenderer] surface with one-finger orbit, two-finger pan and pinch zoom
 * ([GaussianCameraController]), the bundled samples and a system file picker along the bottom, loading and error
 * states, and a debug overlay with fps, frame time, render scale and splat count.
 */
@Composable
fun ViewerScreen(
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val camera = remember { GaussianCameraController() }
    var stats by remember { mutableStateOf<RenderStats?>(null) }
    val renderer = remember { SplatRenderer(camera, onStats = { stats = it }) }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(ViewerIntent.Open(SplatSource.Document(uri.toString())))
    }

    val load = state.load
    val loaded = (load as? ViewerLoad.Loaded)?.scene
    LaunchedEffect(loaded) {
        if (loaded != null) {
            val framing = loaded.framing
            synchronized(camera) {
                camera.reset()
                camera.setTarget(framing.target)
                camera.setDistance(framing.distance)
                camera.setFarPlane(max(FAR_PLANE_MIN, (framing.distance + framing.radius) * FAR_PLANE_FACTOR))
            }
        }
        renderer.setScene(loaded?.render)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        SplatSurface(renderer = renderer, camera = camera, modifier = Modifier.fillMaxSize())

        when (load) {
            ViewerLoad.Idle -> CentreMessage(stringResource(R.string.viewer_pick_hint))
            is ViewerLoad.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.viewer_loading, load.source.name),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
                )
            }
            is ViewerLoad.Failed -> Surface(
                modifier = Modifier.align(Alignment.Center).padding(SplatDimens.SpacingLarge),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(SplatDimens.SpacingDefault)) {
                    Text(
                        text = stringResource(R.string.viewer_error_title, load.source.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = load.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = SplatDimens.SpacingSmall),
                    )
                    TextButton(onClick = { viewModel.onIntent(ViewerIntent.Retry) }) {
                        Text(stringResource(R.string.viewer_retry))
                    }
                }
            }
            is ViewerLoad.Loaded -> Unit
        }

        val current = stats
        if (state.showStats && loaded != null && current != null) {
            Text(
                text = statsLine(current),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(SplatDimens.SpacingSmall)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(SplatDimens.SpacingXSmall),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(SplatDimens.SpacingDefault),
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            state.samples.forEach { sample ->
                AssistChip(
                    onClick = { viewModel.onIntent(ViewerIntent.Open(sample)) },
                    label = { Text(sample.name) },
                )
            }
            AssistChip(
                onClick = { picker.launch(arrayOf("*/*")) },
                label = { Text(stringResource(R.string.viewer_open_file)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
            )
            TextButton(onClick = { viewModel.onIntent(ViewerIntent.ToggleStats) }) {
                Text(stringResource(R.string.viewer_toggle_stats))
            }
        }
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

/** A GLSurfaceView (GLES 3.0, context kept across pause) driven by [renderer], paused and resumed with the screen. */
@Composable
private fun SplatSurface(renderer: SplatRenderer, camera: GaussianCameraController, modifier: Modifier) {
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
        modifier = modifier.pointerInput(camera) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    synchronized(camera) {
                        if (fingers >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom > 0f) camera.onZoom(1f / zoom)
                            camera.onPan(-pan.x * PAN_PER_PX, pan.y * PAN_PER_PX)
                        } else if (fingers == 1) {
                            camera.onRotate(pan.x * ORBIT_PER_PX, pan.y * ORBIT_PER_PX)
                        }
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
            }
        },
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
private const val FAR_PLANE_MIN = 100f
private const val FAR_PLANE_FACTOR = 10f
