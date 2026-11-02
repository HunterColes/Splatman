package com.huntercoles.splatman.core.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The Theme setting. [System] (the default) follows the phone. */
enum class ThemeMode { System, Light, Dark }

@Singleton
class ThemePreferences(private val store: PreferenceStore) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPreferenceStore(context, FILE))

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        store.putString(THEME_MODE_KEY, mode.name)
        _themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode {
        val stored = store.getString(THEME_MODE_KEY)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    companion object {
        private const val FILE = "theme_prefs"
        private const val THEME_MODE_KEY = "theme_mode"
    }
}
