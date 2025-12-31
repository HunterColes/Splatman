package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the Viewer screen
 *
 * Manages:
 * - Camera permission state
 * - Camera enabled/disabled state
 * - Camera preview state
 */
@HiltViewModel
class ViewerViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    fun onCameraPermissionGranted() {
        _uiState.update { it.copy(
            isCameraPermissionGranted = true,
            isCameraEnabled = true
        ) }
    }

    fun onCameraPermissionDenied() {
        _uiState.update { it.copy(
            isCameraPermissionGranted = false,
            isCameraEnabled = false
        ) }
    }

    fun onCameraButtonClicked() {
        if (_uiState.value.isCameraPermissionGranted) {
            // Toggle camera on/off
            _uiState.update { it.copy(
                isCameraEnabled = !it.isCameraEnabled
            ) }
        } else {
            // Request permission - this will be handled by the composable
            _uiState.update { it.copy(
                shouldRequestPermission = true
            ) }
        }
    }

    fun onPermissionRequestHandled() {
        _uiState.update { it.copy(
            shouldRequestPermission = false
        ) }
    }
}

data class ViewerUiState(
    val isCameraPermissionGranted: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val shouldRequestPermission: Boolean = false
)