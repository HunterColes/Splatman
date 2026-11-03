package com.huntercoles.splatman.library.data.prefs

import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.library.domain.LibrarySort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An in-memory [PreferenceStore]: a new preferences object over the same store reads what the last one wrote. */
private class MapPreferenceStore : PreferenceStore {
    val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/** #310: the remembered Library sort, ADR 0013 point 10: key `sort`, stored by enum name, default NEWEST. */
class LibraryPreferencesTest {

    @Test
    fun `A5 the sort defaults to NEWEST`() {
        assertEquals(LibrarySort.NEWEST, LibraryPreferences(MapPreferenceStore()).sort.value)
    }

    @Test
    fun `A5 a stored sort survives a new instance over the same store`() {
        val store = MapPreferenceStore()
        val prefs = LibraryPreferences(store)
        prefs.setSort(LibrarySort.SIZE)
        assertEquals(LibrarySort.SIZE, prefs.sort.value)
        assertEquals("SIZE", store.values["sort"])
        assertEquals(LibrarySort.SIZE, LibraryPreferences(store).sort.value)
        LibraryPreferences(store).setSort(LibrarySort.NAME)
        assertEquals(LibrarySort.NAME, LibraryPreferences(store).sort.value)
    }

    @Test
    fun `A5 an unknown stored string reads NEWEST`() {
        for (junk in listOf("BIGGEST", "", "newest", "Name")) {
            val store = MapPreferenceStore().apply { putString("sort", junk) }
            assertEquals(LibrarySort.NEWEST, LibraryPreferences(store).sort.value, "stored '$junk'")
        }
    }
}
