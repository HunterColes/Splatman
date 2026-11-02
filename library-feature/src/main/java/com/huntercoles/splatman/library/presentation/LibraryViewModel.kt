package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

data class LibraryUiState(
    val loading: Boolean = true,
    val scans: List<Scan> = emptyList(),
    val samples: List<Sample> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    sampleRepository: SampleRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> =
        combine(scanRepository.scans(), flow { emit(sampleRepository.samples()) }) { scans, samples ->
            LibraryUiState(loading = false, scans = scans, samples = samples)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    init {
        viewModelScope.launch {
            try {
                scanRepository.sync()
            } catch (e: IOException) {
                Timber.w(e, "Scan folder sync failed")
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { scanRepository.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                scanRepository.delete(id)
            } catch (e: IOException) {
                Timber.w(e, "Deleting scan %s failed", id)
            }
        }
    }
}
