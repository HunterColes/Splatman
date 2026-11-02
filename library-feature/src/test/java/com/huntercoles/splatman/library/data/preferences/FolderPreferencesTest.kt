package com.huntercoles.splatman.library.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the FolderPreferences SharedPreferences wrapper. Plain JVM: the SharedPreferences are an
 * in-memory fake and `Uri.parse` is mocked (Jupiter has no Robolectric runner).
 */
class FolderPreferencesTest {

    private val store = FakeSharedPreferences()
    private lateinit var preferences: FolderPreferences

    @BeforeEach
    fun setup() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val text = firstArg<String>()
            if (text == UNPARSEABLE) throw IllegalArgumentException("bad uri")
            mockk<Uri> { every { this@mockk.toString() } returns text }
        }
        val context = mockk<Context> {
            every { getSharedPreferences("splatman_folder_prefs", Context.MODE_PRIVATE) } returns store
        }
        preferences = FolderPreferences(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `initially no folder configured`() {
        assertNull(preferences.getFolderUri(), "Should have no folder initially")
        assertFalse(preferences.hasFolderConfigured(), "Should not be configured")
    }

    @Test
    fun `save and retrieve folder URI`() {
        val text = "content://com.android.externalstorage.documents/tree/primary:Download/PLY"

        preferences.setFolderUri(Uri.parse(text))

        val retrieved = preferences.getFolderUri()
        assertNotNull(retrieved, "Should retrieve saved URI")
        assertEquals(text, retrieved.toString(), "URIs should match")
        assertTrue(preferences.hasFolderConfigured(), "Should be configured")
    }

    @Test
    fun `clear folder removes URI and last scan`() {
        preferences.setFolderUri(Uri.parse("content://test/folder"))
        preferences.updateLastScan()

        preferences.clearFolder()

        assertNull(preferences.getFolderUri(), "URI should be cleared")
        assertFalse(preferences.hasFolderConfigured(), "Should not be configured")
        assertEquals(0L, preferences.getLastScanTimestamp(), "Last scan should be reset")
    }

    @Test
    fun `update last scan timestamp`() {
        val beforeTime = System.currentTimeMillis()
        preferences.updateLastScan()
        val afterTime = System.currentTimeMillis()

        assertTrue(preferences.getLastScanTimestamp() in beforeTime..afterTime, "Timestamp should be recent")
    }

    @Test
    fun `auto scan enabled by default`() {
        assertTrue(preferences.isAutoScanEnabled(), "Auto scan should be enabled by default")
    }

    @Test
    fun `toggle auto scan preference`() {
        preferences.setAutoScanEnabled(false)
        assertFalse(preferences.isAutoScanEnabled(), "Auto scan should be disabled")

        preferences.setAutoScanEnabled(true)
        assertTrue(preferences.isAutoScanEnabled(), "Auto scan should be enabled")
    }

    @Test
    fun `unparseable stored URI returns null`() {
        store.edit().putString("folder_uri", UNPARSEABLE).apply()

        assertNull(preferences.getFolderUri(), "Unparseable URI should return null")
        assertFalse(preferences.hasFolderConfigured(), "Should not be configured")
    }

    private companion object {
        const val UNPARSEABLE = "not a valid uri !@#$%"
    }
}

/** Minimal in-memory SharedPreferences: enough for FolderPreferences, no listeners. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as MutableSet<String>? ?: defValues
    }
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
