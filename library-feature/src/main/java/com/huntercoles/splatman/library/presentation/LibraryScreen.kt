package com.huntercoles.splatman.library.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.presentation.components.GaussianSplatViewer
import com.huntercoles.splatman.library.presentation.components.SceneInfoOverlay
import com.huntercoles.splatman.library.presentation.components.LoadingIndicator
import com.huntercoles.splatman.library.presentation.components.InternalModelsGrid
import com.huntercoles.splatman.library.presentation.components.ExternalModelsGrid

/**
 * Redesigned Library Screen - Internal/External Model Folders
 * 
 * New layout:
 * - Full-screen 3D renderer with border
 * - Top-right: Folder picker + Info buttons
 * - Bottom: Internal/External folder tabs with grid view
 * - Loading indicator during PLY loading
 * - Seamless interaction with rendered models
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // State for UI
    var showInfoOverlay by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Internal, 1 = External
    
    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.onFolderSelected(it) }
    }
    
    // Launch folder picker when requested
    LaunchedEffect(uiState.shouldShowFolderPicker) {
        if (uiState.shouldShowFolderPicker) {
            folderPickerLauncher.launch(null)
            viewModel.onFolderPickerShown()
        }
    }
    
    // Show error messages
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }
    
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = SplatColors.SplatBlack
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Top bar with buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SplatDimens.SpacingDefault),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Folder picker button
                IconButton(
                    onClick = { viewModel.onPickFolderClicked() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SplatColors.DarkPurple.copy(alpha = 0.9f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Select PLY folder",
                        tint = SplatColors.SplatGold,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Info button
                if (uiState.selectedScene != null) {
                    IconButton(
                        onClick = { showInfoOverlay = !showInfoOverlay },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SplatColors.DarkPurple.copy(alpha = 0.9f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Show scene info",
                            tint = SplatColors.SplatGold,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // Main content area
            Box(modifier = Modifier.weight(1f)) {
                // 3D Renderer with border
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .border(
                            width = 2.dp,
                            color = SplatColors.SplatGold.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    GaussianSplatViewer(
                        scene = uiState.selectedScene,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Loading indicator overlay
                    if (uiState.isLoading) {
                        LoadingIndicator(
                            progress = uiState.loadingProgress,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                
                // Info overlay
                if (showInfoOverlay && uiState.selectedScene != null) {
                    SceneInfoOverlay(
                        scene = uiState.selectedScene!!,
                        onDismiss = { showInfoOverlay = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 72.dp, end = 24.dp)
                    )
                }
            }
            
            // Bottom folder tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SplatColors.DeepPurple,
                contentColor = SplatColors.SplatGold,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Internal Models") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("External Models") }
                )
            }
            
            // Model grid
            Box(modifier = Modifier.height(200.dp)) {
                when (selectedTab) {
                    0 -> InternalModelsGrid(
                        onModelClick = { scene ->
                            viewModel.selectScene(scene)
                            showInfoOverlay = false
                        }
                    )
                    1 -> ExternalModelsGrid(
                        scenes = scenes,
                        selectedScene = uiState.selectedScene,
                        onModelClick = { scene ->
                            viewModel.selectScene(scene)
                            showInfoOverlay = false
                        }
                    )
                }
            }
        }
    }
}
