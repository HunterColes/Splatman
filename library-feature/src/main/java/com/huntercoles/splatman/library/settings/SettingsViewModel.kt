package com.huntercoles.splatman.library.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.core.preferences.ExportFormat
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.core.preferences.ThemeMode
import com.huntercoles.splatman.core.preferences.ThemePreferences
import com.huntercoles.splatman.library.data.storage.ScanStorage
import com.huntercoles.splatman.library.data.storage.StorageSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** The Settings screen. [storage] is null while it is being counted. */
data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.System,
    val haptics: Boolean = SettingsPreferences.DEFAULT_HAPTICS,
    val keepFrames: Boolean = SettingsPreferences.DEFAULT_KEEP_FRAMES,
    val exportFormat: ExportFormat = SettingsPreferences.DEFAULT_EXPORT_FORMAT,
    val storage: StorageSummary? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val settingsPreferences: SettingsPreferences,
    private val storage: ScanStorage,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val summary = MutableStateFlow<StorageSummary?>(null)
    private val freed = Channel<Long>(Channel.BUFFERED)

    /** Bytes freed by each Clear capture frames, for its snackbar. */
    val framesFreed: Flow<Long> = freed.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        themePreferences.themeMode,
        settingsPreferences.haptics,
        settingsPreferences.keepFrames,
        settingsPreferences.exportFormat,
        summary,
    ) { theme, haptics, keepFrames, format, storage ->
        SettingsUiState(theme, haptics, keepFrames, format, storage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    init {
        viewModelScope.launch { summary.value = withContext(io) { storage.measure() } }
    }

    fun setTheme(mode: ThemeMode) = themePreferences.setThemeMode(mode)

    fun setHaptics(enabled: Boolean) = settingsPreferences.setHaptics(enabled)

    fun setKeepFrames(keep: Boolean) = settingsPreferences.setKeepFrames(keep)

    fun setExportFormat(format: ExportFormat) = settingsPreferences.setExportFormat(format)

    fun clearFrames() {
        viewModelScope.launch {
            summary.value = null
            val bytes = withContext(io) { storage.clearFrames() }
            summary.value = withContext(io) { storage.measure() }
            freed.send(bytes)
        }
    }
}
