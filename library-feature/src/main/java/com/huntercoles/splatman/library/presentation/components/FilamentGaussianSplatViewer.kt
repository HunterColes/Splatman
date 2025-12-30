package com.huntercoles.splatman.library.presentation.components

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import timber.log.Timber

/**
 * OpenGL ES 2.0 Gaussian Splat Viewer (Compose wrapper)
 * 
 * BULLETPROOF DESIGN: Simple GLES20 - no Filament materials needed
 */
@Composable
fun FilamentGaussianSplatViewer(
    scene: SplatScene?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraController = remember { 
        GaussianCameraController().apply {
            setAspectRatio(1f)
        }
    }
    var glRenderer by remember { mutableStateOf<SimpleGLPointCloudRenderer?>(null) }
    
    if (scene != null && scene.gaussians.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "🚧 Model Not Yet Loaded",
                    color = SplatColors.SplatGold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${scene.name}\n\nLoading...",
                    color = SplatColors.SplatGold.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }
    
    LaunchedEffect(scene) {
        scene?.let { splatScene ->
            if (splatScene.gaussians.isNotEmpty()) {
                glRenderer?.loadScene(splatScene)
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            glRenderer?.destroy()
            glRenderer = null
            Timber.d("OpenGL viewer disposed")
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    if (zoom != 1f) {
                        cameraController.onZoom(1f / zoom)
                    }
                    if (rotation != 0f) {
                        val deltaX = rotation * 0.1f
                        cameraController.onRotate(deltaX, 0f)
                    }
                    if (pan.x != 0f || pan.y != 0f) {
                        cameraController.onPan(pan.x * 0.001f, -pan.y * 0.001f)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    
                    val newRenderer = SimpleGLPointCloudRenderer(ctx, cameraController)
                    setRenderer(newRenderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    
                    glRenderer = newRenderer
                    Timber.d("OpenGL renderer initialized")
                    
                    scene?.let { 
                        Timber.d("Loading scene: ${it.name} with ${it.gaussians.size} gaussians")
                        post { newRenderer.loadScene(it) }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        AxisWidget(
            cameraController = cameraController,
            modifier = Modifier
        )
    }
}
