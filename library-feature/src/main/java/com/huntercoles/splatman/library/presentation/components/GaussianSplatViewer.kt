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
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.math.Vector3
import timber.log.Timber

/**
 * OpenGL ES 3.0 Gaussian Splat Viewer (Compose wrapper)
 *
 * BULLETPROOF DESIGN: Modern GLES30 - VAOs, UBOs, and GLSL 3.0
 */
@Composable
fun GaussianSplatViewer(
    scene: SplatScene?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraController = remember {
        GaussianCameraController().apply {
            setAspectRatio(1f)
        }
    }
    
    var renderer by remember { mutableStateOf<SimpleGLPointCloudRenderer?>(null) }
    
    // Counter to trigger axis widget updates
    var gestureCounter by remember { mutableStateOf(0) }

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

    DisposableEffect(Unit) {
        onDispose {
            renderer?.destroy()
            renderer = null
            Timber.d("OpenGL viewer disposed")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    var changed = false
                    if (zoom != 1f) {
                        cameraController.onZoom(1f / zoom)
                        changed = true
                    }
                    if (rotation != 0f) {
                        val deltaX = rotation * 0.1f
                        cameraController.onRotate(deltaX, 0f)
                        changed = true
                    }
                    if (pan.x != 0f || pan.y != 0f) {
                        // Use pan for orbital rotation around the target
                        cameraController.onRotate(pan.x * 0.01f, -pan.y * 0.01f)
                        changed = true
                    }
                    if (changed) {
                        gestureCounter++
                    }
                }
            }
    ) {
        key(scene?.id ?: "empty") {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)

                        val newRenderer = SimpleGLPointCloudRenderer(ctx, cameraController)
                        setRenderer(newRenderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                        // Store references
                        renderer = newRenderer
                        
                        Timber.d("OpenGL ES 3.0 renderer initialized")
                    }
                },
                update = { glView ->
                    // Update is called when the AndroidView recomposes
                    // Since we use key(scene), this will be called when scene changes
                    scene?.let { splatScene ->
                        renderer?.setPendingScene(splatScene)
                        // Position camera to view the scene
                        cameraController.reset()
                        val center = Vector3.fromFloatArray(splatScene.boundingBox.center)
                        cameraController.setTarget(center)
                        
                        // Calculate optimal camera distance and far plane to fit the entire scene
                        // Uses bounding sphere approach: position camera so the sphere containing the entire model fits in FOV
                        val boundingBox = splatScene.boundingBox
                        val boundingSphereRadius = boundingBox.diagonal / 2f
                        
                        // Distance to fit bounding sphere in FOV: d = r / tan(fov/2)
                        val fovRad = Math.toRadians(ViewerConstants.FOV_DEGREES.toDouble())
                        val tanHalfFov = Math.tan(fovRad / 2.0).toFloat()
                        val fovBasedDistance = boundingSphereRadius / tanHalfFov
                        
                        // Ensure camera doesn't clip near plane: d > near + radius
                        val nearBasedDistance = ViewerConstants.NEAR_PLANE + boundingSphereRadius
                        
                        // Optimal distance is the maximum requirement
                        val optimalDistance = maxOf(fovBasedDistance, nearBasedDistance, ViewerConstants.MIN_DISTANCE.toFloat())
                        
                        // Far plane must include the farthest point: far > d + radius + margin
                        val requiredFarPlane = optimalDistance + boundingSphereRadius + 10f
                        
                        // Update camera parameters
                        cameraController.setFarPlane(maxOf(requiredFarPlane, ViewerConstants.FAR_PLANE.toFloat()))
                        
                        // Update the renderer's projection matrix with new far plane
                        renderer?.updateProjection()
                        
                        // Set camera distance directly for precise initial positioning
                        cameraController.setDistance(optimalDistance)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        AxisWidget(
            cameraController = cameraController,
            gestureCounter = gestureCounter,
            modifier = Modifier
        )
    }
}
