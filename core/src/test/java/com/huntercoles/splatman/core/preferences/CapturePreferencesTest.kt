package com.huntercoles.splatman.core.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class MemoryStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
}

class CapturePreferencesTest {
    @Test
    fun defaultsToObject() {
        assertEquals("object", CapturePreferences(MemoryStore()).scanMode.value)
    }

    @Test
    fun newInstanceReadsWhatWasSet() {
        val store = MemoryStore()
        val prefs = CapturePreferences(store)
        prefs.setScanMode("room")
        assertEquals("room", prefs.scanMode.value)
        assertEquals("room", CapturePreferences(store).scanMode.value)
    }

    @Test
    fun unknownStoredValueReadsAsObject() {
        val store = MemoryStore()
        store.putString("scan_mode", "outdoor")
        assertEquals("object", CapturePreferences(store).scanMode.value)
    }
}
