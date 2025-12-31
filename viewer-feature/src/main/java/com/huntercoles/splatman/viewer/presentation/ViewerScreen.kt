package com.huntercoles.splatman.viewer.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import timber.log.Timber

/**
 * Viewer Screen - 3D Gaussian Splat Viewer with Camera Access
 *
 * Layout:
 * - Full-screen camera preview area with yellow border
 * - Bottom center: Camera toggle button (purple with gold icon)
 * - Default state: Grayed out with disabled camera icon
 * - Active state: Camera preview enabled
 */
@Composable
fun ViewerScreen(
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Camera provider state
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    
    // Initialize camera provider
    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider
    }
    
    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onCameraPermissionGranted()
        } else {
            viewModel.onCameraPermissionDenied()
        }
        viewModel.onPermissionRequestHandled()
    }
    
    // Handle permission request
    LaunchedEffect(uiState.shouldRequestPermission) {
        if (uiState.shouldRequestPermission) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
    
    Scaffold(
        modifier = modifier,
        containerColor = SplatColors.SplatBlack
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Main camera preview area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .border(
                        width = 2.dp,
                        color = SplatColors.SplatGold.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (uiState.isCameraEnabled) {
                            Color.Black // Camera preview background
                        } else {
                            SplatColors.DeepPurple.copy(alpha = 0.3f) // Grayed out
                        }
                    )
            ) {
                if (!uiState.isCameraEnabled) {
                    // Disabled state overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.7f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        // Camera icon with cross
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Camera disabled",
                                tint = SplatColors.CardWhite.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            // Cross overlay
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.displayLarge,
                                color = SplatColors.ErrorRed,
                                modifier = Modifier.offset(y = (-8).dp)
                            )
                        }
                    }
                } else {
                    // Camera preview
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            if (uiState.isCameraEnabled && uiState.isCameraPermissionGranted) {
                                cameraProvider?.let { provider ->
                                    try {
                                        val preview = Preview.Builder().build()
                                        preview.setSurfaceProvider(previewView.surfaceProvider)
                                        
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview
                                        )
                                    } catch (e: Exception) {
                                        Timber.e(e, "Failed to start camera preview")
                                    }
                                }
                            } else {
                                // Unbind camera when disabled
                                cameraProvider?.unbindAll()
                            }
                        }
                    )
                }
            }

            // Camera toggle button - bottom center
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp) // Above navigation bar
            ) {
                IconButton(
                    onClick = { viewModel.onCameraButtonClicked() },
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(SplatColors.MediumPurple)
                        .border(
                            width = 2.dp,
                            color = SplatColors.SplatGold,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Toggle camera",
                        tint = if (uiState.isCameraEnabled) {
                            SplatColors.SplatGold
                        } else {
                            SplatColors.CardWhite.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}