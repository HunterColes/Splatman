package com.huntercoles.splatman.core.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** An in-memory [PreferenceStore]: a new preferences object over the same store reads what the last one wrote. */
private class MapPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class SettingsPreferencesTest {

    @Test
    fun `theme defaults to match system`() {
        assertEquals(ThemeMode.System, ThemePreferences(MapPreferenceStore()).themeMode.value)
    }

    @Test
    fun `theme persists`() {
        val store = MapPreferenceStore()
        ThemePreferences(store).setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, ThemePreferences(store).themeMode.value)
    }

    @Test
    fun `an unknown stored theme reads as match system`() {
        val store = MapPreferenceStore().apply { putString("theme_mode", "Sepia") }
        assertEquals(ThemeMode.System, ThemePreferences(store).themeMode.value)
    }

    @Test
    fun `haptics default on and persist`() {
        val store = MapPreferenceStore()
        val prefs = SettingsPreferences(store)
        assertTrue(prefs.haptics.value)
        prefs.setHaptics(false)
        assertFalse(prefs.haptics.value)
        assertFalse(SettingsPreferences(store).haptics.value)
    }

    @Test
    fun `keep frames defaults on and persists`() {
        val store = MapPreferenceStore()
        val prefs = SettingsPreferences(store)
        assertTrue(prefs.keepFrames.value)
        prefs.setKeepFrames(false)
        assertFalse(prefs.keepFrames.value)
        assertFalse(SettingsPreferences(store).keepFrames.value)
    }

    @Test
    fun `export format defaults to PLY and persists`() {
        val store = MapPreferenceStore()
        val prefs = SettingsPreferences(store)
        assertEquals(ExportFormat.Ply, prefs.exportFormat.value)
        prefs.setExportFormat(ExportFormat.Splat)
        assertEquals(ExportFormat.Splat, prefs.exportFormat.value)
        assertEquals(ExportFormat.Splat, SettingsPreferences(store).exportFormat.value)
    }

    @Test
    fun `an unknown stored export format reads as PLY`() {
        val store = MapPreferenceStore().apply { putString("export_format", "obj") }
        assertEquals(ExportFormat.Ply, SettingsPreferences(store).exportFormat.value)
    }
}
