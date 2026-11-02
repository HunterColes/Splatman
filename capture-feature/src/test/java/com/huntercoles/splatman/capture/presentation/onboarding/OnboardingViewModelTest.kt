package com.huntercoles.splatman.capture.presentation.onboarding

import androidx.lifecycle.SavedStateHandle
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val pending = CompletableDeferred<TrackingAvailability>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        page: Int? = null,
        check: ArAvailabilityCheck = ArAvailabilityCheck { pending.await() },
    ): OnboardingViewModel {
        val handle = SavedStateHandle(if (page == null) emptyMap() else mapOf(OnboardingViewModel.PAGE_KEY to page))
        return OnboardingViewModel(handle, check)
    }

    private fun answered(availability: TrackingAvailability) = viewModel(check = { availability })

    @Test
    fun `starts on the welcome page with nothing decided`() {
        val state = viewModel().uiState.value
        assertEquals(OnboardingPage.WELCOME, state.page)
        assertEquals(ArState.CHECKING, state.ar)
        assertNull(state.exit)
        assertEquals(3, state.pageCount)
    }

    @Test
    fun `next walks the three pages and stops on the last`() {
        val vm = viewModel()
        vm.onIntent(OnboardingIntent.Next)
        assertEquals(OnboardingPage.TIPS, vm.uiState.value.page)
        vm.onIntent(OnboardingIntent.Next)
        assertEquals(OnboardingPage.CAMERA, vm.uiState.value.page)
        vm.onIntent(OnboardingIntent.Next)
        assertEquals(OnboardingPage.CAMERA, vm.uiState.value.page)
        assertNull(vm.uiState.value.exit)
    }

    @Test
    fun `a swipe moves to the shown page, clamped into range`() {
        val vm = viewModel()
        vm.onIntent(OnboardingIntent.PageShown(2))
        assertEquals(OnboardingPage.CAMERA, vm.uiState.value.page)
        vm.onIntent(OnboardingIntent.PageShown(0))
        assertEquals(OnboardingPage.WELCOME, vm.uiState.value.page)
        vm.onIntent(OnboardingIntent.PageShown(7))
        assertEquals(OnboardingPage.CAMERA, vm.uiState.value.page)
        vm.onIntent(OnboardingIntent.PageShown(-1))
        assertEquals(OnboardingPage.WELCOME, vm.uiState.value.page)
    }

    @Test
    fun `settings opens it at page 2`() {
        assertEquals(OnboardingPage.TIPS, viewModel(page = 1).uiState.value.page)
    }

    @Test
    fun `a bad page argument opens the nearest page`() {
        assertEquals(OnboardingPage.CAMERA, viewModel(page = 9).uiState.value.page)
    }

    @Test
    fun `skip goes straight to the library`() {
        val vm = viewModel()
        vm.onIntent(OnboardingIntent.Skip)
        assertEquals(OnboardingExit.LIBRARY, vm.uiState.value.exit)
        assertEquals(OnboardingPage.WELCOME, vm.uiState.value.page)
    }

    @Test
    fun `nothing moves once onboarding is over`() {
        val vm = viewModel()
        vm.onIntent(OnboardingIntent.Skip)
        vm.onIntent(OnboardingIntent.Next)
        vm.onIntent(OnboardingIntent.OpenFile)
        assertEquals(OnboardingPage.WELCOME, vm.uiState.value.page)
        assertEquals(OnboardingExit.LIBRARY, vm.uiState.value.exit)
    }

    @Test
    fun `permission granted goes to the library`() {
        val vm = answered(TrackingAvailability.SUPPORTED)
        vm.onIntent(OnboardingIntent.PageShown(2))
        vm.onIntent(OnboardingIntent.PermissionResult(granted = true))
        assertEquals(true, vm.uiState.value.permissionGranted)
        assertEquals(OnboardingExit.LIBRARY, vm.uiState.value.exit)
    }

    @Test
    fun `permission denied still goes to the library`() {
        val vm = answered(TrackingAvailability.SUPPORTED)
        vm.onIntent(OnboardingIntent.PermissionResult(granted = false))
        assertEquals(false, vm.uiState.value.permissionGranted)
        assertEquals(OnboardingExit.LIBRARY, vm.uiState.value.exit)
    }

    @ParameterizedTest
    @EnumSource(TrackingAvailability::class)
    fun `each availability answer picks the last page`(availability: TrackingAvailability) {
        val expected = when (availability) {
            TrackingAvailability.SUPPORTED -> ArState.SUPPORTED
            TrackingAvailability.INSTALL_NEEDED -> ArState.INSTALL_NEEDED
            TrackingAvailability.UNSUPPORTED -> ArState.UNSUPPORTED
        }
        assertEquals(expected, answered(availability).uiState.value.ar)
    }

    @Test
    fun `the camera page shows while the check is still running`() {
        val vm = viewModel()
        assertEquals(ArState.CHECKING, vm.uiState.value.ar)
        pending.complete(TrackingAvailability.UNSUPPORTED)
        assertEquals(ArState.UNSUPPORTED, vm.uiState.value.ar)
    }

    @Test
    fun `unsupported offers open a file and continue`() {
        val open = answered(TrackingAvailability.UNSUPPORTED)
        open.onIntent(OnboardingIntent.OpenFile)
        assertEquals(OnboardingExit.IMPORT, open.uiState.value.exit)

        val later = answered(TrackingAvailability.UNSUPPORTED)
        later.onIntent(OnboardingIntent.Continue)
        assertEquals(OnboardingExit.LIBRARY, later.uiState.value.exit)
    }

    @Test
    fun `install finished turns the page into the camera page`() {
        val vm = answered(TrackingAvailability.INSTALL_NEEDED)
        vm.onIntent(OnboardingIntent.InstallResult(TrackingAvailability.SUPPORTED))
        assertEquals(ArState.SUPPORTED, vm.uiState.value.ar)
        assertFalse(vm.uiState.value.installPending)
        assertNull(vm.uiState.value.exit)
    }

    @Test
    fun `install under way stays on the install page and asks again on resume`() {
        val vm = answered(TrackingAvailability.INSTALL_NEEDED)
        vm.onIntent(OnboardingIntent.InstallResult(TrackingAvailability.INSTALL_NEEDED))
        assertEquals(ArState.INSTALL_NEEDED, vm.uiState.value.ar)
        assertTrue(vm.uiState.value.installPending)
    }

    @Test
    fun `install refused by the phone turns into the unsupported page`() {
        val vm = answered(TrackingAvailability.INSTALL_NEEDED)
        vm.onIntent(OnboardingIntent.InstallResult(TrackingAvailability.UNSUPPORTED))
        assertEquals(ArState.UNSUPPORTED, vm.uiState.value.ar)
        assertFalse(vm.uiState.value.installPending)
    }

    @Test
    fun `later on the install page goes to the library`() {
        val vm = answered(TrackingAvailability.INSTALL_NEEDED)
        vm.onIntent(OnboardingIntent.Later)
        assertEquals(OnboardingExit.LIBRARY, vm.uiState.value.exit)
    }

    @Test
    fun `a late check answer does not undo an install result`() {
        val vm = viewModel()
        vm.onIntent(OnboardingIntent.InstallResult(TrackingAvailability.SUPPORTED))
        pending.complete(TrackingAvailability.INSTALL_NEEDED)
        assertEquals(ArState.SUPPORTED, vm.uiState.value.ar)
    }
}
