package com.huntercoles.splatman.viewer.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: ViewerViewModel

    @Before
    fun setUp() {
        viewModel = ViewerViewModel()
    }

    @After
    fun tearDown() {
        // Clean up if needed
    }

    @Test
    fun `initial state should have camera disabled and no permission granted`() = runTest(testDispatcher) {
        val uiState = viewModel.uiState.value
        assertFalse(uiState.isCameraPermissionGranted)
        assertFalse(uiState.isCameraEnabled)
        assertFalse(uiState.shouldRequestPermission)
    }

    @Test
    fun `onCameraPermissionGranted should enable camera and set permission granted`() = runTest(testDispatcher) {
        viewModel.onCameraPermissionGranted()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.isCameraPermissionGranted)
        assertTrue(uiState.isCameraEnabled)
    }

    @Test
    fun `onCameraPermissionDenied should disable camera and set permission denied`() = runTest(testDispatcher) {
        viewModel.onCameraPermissionDenied()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.isCameraPermissionGranted)
        assertFalse(uiState.isCameraEnabled)
    }

    @Test
    fun `onCameraButtonClicked should request permission when not granted`() = runTest(testDispatcher) {
        viewModel.onCameraButtonClicked()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.shouldRequestPermission)
    }

    @Test
    fun `onCameraButtonClicked should toggle camera when permission granted`() = runTest(testDispatcher) {
        // First grant permission
        viewModel.onCameraPermissionGranted()
        assertTrue(viewModel.uiState.value.isCameraEnabled)

        // Click to disable
        viewModel.onCameraButtonClicked()
        assertFalse(viewModel.uiState.value.isCameraEnabled)

        // Click to enable again
        viewModel.onCameraButtonClicked()
        assertTrue(viewModel.uiState.value.isCameraEnabled)
    }

    @Test
    fun `onPermissionRequestHandled should reset permission request flag`() = runTest(testDispatcher) {
        viewModel.onCameraButtonClicked()
        assertTrue(viewModel.uiState.value.shouldRequestPermission)

        viewModel.onPermissionRequestHandled()
        assertFalse(viewModel.uiState.value.shouldRequestPermission)
    }
}