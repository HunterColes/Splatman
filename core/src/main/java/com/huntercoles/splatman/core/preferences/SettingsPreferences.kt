package com.huntercoles.splatman.core.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The default export format: PLY (the default) or the smaller .splat. */
enum class ExportFormat { Ply, Splat }

/** The Settings switches and choices other than Theme (docs/design/screens/settings.md). */
@Singleton
class SettingsPreferences(private val store: PreferenceStore) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPreferenceStore(context, FILE))

    private val _haptics = MutableStateFlow(store.getBoolean(HAPTICS_KEY, DEFAULT_HAPTICS))

    /** Vibration feedback; on by default. */
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _keepFrames = MutableStateFlow(store.getBoolean(KEEP_FRAMES_KEY, DEFAULT_KEEP_FRAMES))

    /** Keep a scan's capture frames after it is built, so it can be rebuilt; on by default. */
    val keepFrames: StateFlow<Boolean> = _keepFrames.asStateFlow()

    private val _exportFormat = MutableStateFlow(readExportFormat())

    /** The format Export picks first; [ExportFormat.Ply] by default. */
    val exportFormat: StateFlow<ExportFormat> = _exportFormat.asStateFlow()

    fun setHaptics(enabled: Boolean) {
        store.putBoolean(HAPTICS_KEY, enabled)
        _haptics.value = enabled
    }

    fun setKeepFrames(keep: Boolean) {
        store.putBoolean(KEEP_FRAMES_KEY, keep)
        _keepFrames.value = keep
    }

    fun setExportFormat(format: ExportFormat) {
        store.putString(EXPORT_FORMAT_KEY, format.name)
        _exportFormat.value = format
    }

    private fun readExportFormat(): ExportFormat {
        val stored = store.getString(EXPORT_FORMAT_KEY)
        return ExportFormat.entries.firstOrNull { it.name == stored } ?: DEFAULT_EXPORT_FORMAT
    }

    companion object {
        private const val FILE = "settings_prefs"
        private const val HAPTICS_KEY = "haptics"
        private const val KEEP_FRAMES_KEY = "keep_frames"
        private const val EXPORT_FORMAT_KEY = "export_format"
        const val DEFAULT_HAPTICS = true
        const val DEFAULT_KEEP_FRAMES = true
        val DEFAULT_EXPORT_FORMAT = ExportFormat.Ply
    }
}
