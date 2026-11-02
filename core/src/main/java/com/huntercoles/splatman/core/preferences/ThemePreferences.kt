package com.huntercoles.splatman.core.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The Theme setting. [System] (the default) follows the phone. */
enum class ThemeMode { System, Light, Dark }

@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    val darkModeEnabled: Flow<Boolean> = themeMode.map { it == ThemeMode.Dark }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE_KEY, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDarkMode(enabled: Boolean) {
        setThemeMode(if (enabled) ThemeMode.Dark else ThemeMode.Light)
    }

    fun toggleDarkMode() {
        setDarkMode(_themeMode.value != ThemeMode.Dark)
    }

    private fun readThemeMode(): ThemeMode {
        val stored = prefs.getString(THEME_MODE_KEY, null)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    companion object {
        private const val THEME_MODE_KEY = "theme_mode"
    }
}
