package com.huntercoles.splatman.capture.presentation.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.capture.tracking.TrackingCheck
import com.huntercoles.splatman.capture.tracking.FlavourTracking
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The AR availability check onboarding runs once (a seam over the flavour's TrackerSupport for tests). */
fun interface ArAvailabilityCheck {
    /** Never throws; a failed check reads as [TrackingAvailability.COULDNT_CHECK] with its reason. */
    suspend fun check(): TrackingCheck
}

/**
 * The onboarding pager: page flow, Skip, the camera permission answer and the AR availability check feeding the
 * last page. The route's `page` argument (Settings > Show scanning tips again passes 1) picks the first page.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val availability: ArAvailabilityCheck,
) : ViewModel() {
    private val state = MutableStateFlow(
        OnboardingUiState(page = OnboardingPage.at(savedStateHandle.get<Int>(PAGE_KEY) ?: 0)),
    )

    /** What the screen shows. */
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    init {
        check()
    }

    /** Handles one [intent]. */
    fun onIntent(intent: OnboardingIntent) {
        val before = state.value
        state.update { reduce(it, intent) }
        if (intent == OnboardingIntent.RetryCheck && before.exit == null) check()
    }

    private fun check() {
        viewModelScope.launch { onIntent(OnboardingIntent.CheckResult(availability.check())) }
    }

    companion object {
        /** The route argument naming the first page (`NavigationDestination.Onboarding.page`). */
        const val PAGE_KEY = "page"
    }
}

/** [ArAvailabilityCheck] on the flavour's tracker (ARCore in `full`, none in `foss`). */
@Module
@InstallIn(ViewModelComponent::class)
object OnboardingModule {
    @Provides
    fun arAvailabilityCheck(@ApplicationContext context: Context): ArAvailabilityCheck {
        val tracker = FlavourTracking.support(context)
        return ArAvailabilityCheck { tracker.check() }
    }
}
