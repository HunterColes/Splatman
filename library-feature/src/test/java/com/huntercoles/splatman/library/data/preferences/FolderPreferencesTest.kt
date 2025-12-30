package com.huntercoles.splatman.library.data.preferences

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for FolderPreferences SharedPreferences wrapper
 */
@RunWith(RobolectricTestRunner::class)
class FolderPreferencesTest {
    
    private lateinit var context: Context
    private lateinit var preferences: FolderPreferences
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = FolderPreferences(context)
    }
    
    @After
    fun tearDown() {
        preferences.clearFolder()
    }
    
    @Test
    fun `initially no folder configured`() {
        assertNull("Should have no folder initially", preferences.getFolderUri())
        assertFalse("Should not be configured", preferences.hasFolderConfigured())
    }
    
    @Test
    fun `save and retrieve folder URI`() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary:Download/PLY")
        
        preferences.setFolderUri(testUri)
        
        val retrieved = preferences.getFolderUri()
        assertNotNull("Should retrieve saved URI", retrieved)
        assertEquals("URIs should match", testUri, retrieved)
        assertTrue("Should be configured", preferences.hasFolderConfigured())
    }
    
    @Test
    fun `clear folder removes URI`() {
        val testUri = Uri.parse("content://test/folder")
        preferences.setFolderUri(testUri)
        
        preferences.clearFolder()
        
        assertNull("URI should be cleared", preferences.getFolderUri())
        assertFalse("Should not be configured", preferences.hasFolderConfigured())
        assertEquals("Last scan should be reset", 0, preferences.getLastScanTimestamp())
    }
    
    @Test
    fun `update last scan timestamp`() {
        val beforeTime = System.currentTimeMillis()
        preferences.updateLastScan()
        val afterTime = System.currentTimeMillis()
        
        val timestamp = preferences.getLastScanTimestamp()
        
        assertTrue("Timestamp should be recent", timestamp in beforeTime..afterTime)
    }
    
    @Test
    fun `auto scan enabled by default`() {
        assertTrue("Auto scan should be enabled by default", preferences.isAutoScanEnabled())
    }
    
    @Test
    fun `toggle auto scan preference`() {
        preferences.setAutoScanEnabled(false)
        assertFalse("Auto scan should be disabled", preferences.isAutoScanEnabled())
        
        preferences.setAutoScanEnabled(true)
        assertTrue("Auto scan should be enabled", preferences.isAutoScanEnabled())
    }
    
    @Test
    fun `invalid URI string returns null`() {
        // Manually corrupt the preference
        context.getSharedPreferences("splatman_folder_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", "not a valid uri !@#$%")
            .apply()
        
        val retrieved = preferences.getFolderUri()
        assertNull("Invalid URI should return null", retrieved)
    }
}
