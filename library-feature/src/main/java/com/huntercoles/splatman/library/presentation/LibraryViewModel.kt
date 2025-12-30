package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.library.domain.model.SplatScene
import com.huntercoles.splatman.library.domain.repository.ExportFormat
import com.huntercoles.splatman.library.domain.repository.SplatSceneRepository
import com.huntercoles.splatman.library.domain.usecase.PickPlyFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the Library screen
 * 
 * Manages:
 * - Scene list from repository
 * - Selected scene for display
 * - Import/export operations
 * - Delete/rename actions
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: SplatSceneRepository,
    private val pickPlyFolderUseCase: PickPlyFolderUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    
    /**
     * All scenes from the repository
     * Reactive - updates automatically when database changes
     */
    val scenes: StateFlow<List<SplatScene>> = repository.getAllScenes()
        .map { scenes ->
            _uiState.update { it.copy(isLoading = false) }
            scenes
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * Select a scene to display in the top panel
     */
    fun selectScene(scene: SplatScene) {
        _uiState.update { it.copy(selectedScene = scene) }
        Timber.d("Selected scene: ${scene.name} (${scene.gaussianCount} Gaussians)")
    }
    
    /**
     * Clear selected scene
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedScene = null) }
    }
    
    /**
     * Import a file from the file system
     */
    fun importFile(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            repository.importFile(file)
                .onSuccess { scene ->
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            selectedScene = scene
                        )
                    }
                    Timber.d("Imported: ${scene.name}")
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            error = "Import failed: ${error.message}"
                        )
                    }
                    Timber.e(error, "Import failed")
                }
        }
    }
    
    /**
     * Export a scene to a file
     */
    fun exportScene(sceneId: String, outputFile: File, format: ExportFormat) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            repository.exportScene(sceneId, format, outputFile)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    Timber.d("Exported to ${outputFile.absolutePath}")
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            error = "Export failed: ${error.message}"
                        )
                    }
                    Timber.e(error, "Export failed")
                }
        }
    }
    
    /**
     * Delete a scene
     */
    fun deleteScene(sceneId: String) {
        viewModelScope.launch {
            repository.deleteScene(sceneId)
                .onSuccess {
                    _uiState.update { 
                        it.copy(
                            selectedScene = if (it.selectedScene?.id == sceneId) null else it.selectedScene
                        )
                    }
                    Timber.d("Deleted scene: $sceneId")
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(error = "Delete failed: ${error.message}")
                    }
                    Timber.e(error, "Delete failed")
                }
        }
    }
    
    /**
     * Rename a scene
     */
    fun renameScene(sceneId: String, newName: String) {
        viewModelScope.launch {
            repository.renameScene(sceneId, newName)
                .onSuccess {
                    Timber.d("Renamed scene: $sceneId to $newName")
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(error = "Rename failed: ${error.message}")
                    }
                    Timber.e(error, "Rename failed")
                }
        }
    }
    
    /**
     * Clear any error messages
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * Trigger folder picker (handled by Activity)
     * Sets a flag that Activity will observe
     */
    fun onPickFolderClicked() {
        _uiState.update { it.copy(shouldShowFolderPicker = true) }
        Timber.d("Folder picker requested")
    }
    
    /**
     * Folder picker was shown (reset flag)
     */
    fun onFolderPickerShown() {
        _uiState.update { it.copy(shouldShowFolderPicker = false) }
    }
    
    /**
     * User selected a folder (from Activity result)
     * Scans folder for PLY files and loads them
     */
    fun onFolderSelected(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            try {
                pickPlyFolderUseCase(uri)
                    .onSuccess { fileCount ->
                        _uiState.update { 
                            it.copy(
                                isLoading = false,
                                error = if (fileCount > 0) {
                                    "Loaded $fileCount models from folder"
                                } else {
                                    "No PLY files found in selected folder"
                                }
                            )
                        }
                        Timber.d("Folder scan complete: $fileCount files")
                    }
                    .onFailure { error ->
                        _uiState.update { 
                            it.copy(
                                isLoading = false,
                                error = "Failed to scan folder: ${error.message}"
                            )
                        }
                        Timber.e(error, "Folder scan failed")
                    }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = "Error loading folder: ${e.message}"
                    )
                }
                Timber.e(e, "Error in onFolderSelected")
            }
        }
    }
}

/**
 * UI state for the Library screen
 */
data class LibraryUiState(
    val isLoading: Boolean = true,
    val loadingProgress: Float = 0f,
    val selectedScene: SplatScene? = null,
    val error: String? = null,
    val shouldShowFolderPicker: Boolean = false
)
