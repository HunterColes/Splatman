package com.huntercoles.splatman.library.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent storage for PLY folder selection
 * 
 * Stores:
 * - Selected folder URI (Android Storage Access Framework)
 * - Last scan timestamp
 * - Auto-scan preference
 */
@Singleton
class FolderPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    /**
     * Get selected PLY folder URI (null if not set)
     */
    fun getFolderUri(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null)
        return uriString?.let {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                Timber.e(e, "Invalid folder URI: $it")
                null
            }
        }
    }
    
    /**
     * Set PLY folder URI (from Storage Access Framework picker)
     */
    fun setFolderUri(uri: Uri) {
        prefs.edit {
            putString(KEY_FOLDER_URI, uri.toString())
            putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
        }
        Timber.d("Saved folder URI: $uri")
    }
    
    /**
     * Clear folder selection
     */
    fun clearFolder() {
        prefs.edit {
            remove(KEY_FOLDER_URI)
            remove(KEY_LAST_SCAN)
        }
        Timber.d("Cleared folder selection")
    }
    
    /**
     * Get last folder scan timestamp (0 if never scanned)
     */
    fun getLastScanTimestamp(): Long {
        return prefs.getLong(KEY_LAST_SCAN, 0)
    }
    
    /**
     * Update last scan timestamp to now
     */
    fun updateLastScan() {
        prefs.edit {
            putLong(KEY_LAST_SCAN, System.currentTimeMillis())
        }
    }
    
    /**
     * Check if auto-scan is enabled (scan folder on app launch)
     */
    fun isAutoScanEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SCAN, true)
    }
    
    /**
     * Set auto-scan preference
     */
    fun setAutoScanEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_AUTO_SCAN, enabled)
        }
    }
    
    /**
     * Check if folder is configured
     */
    fun hasFolderConfigured(): Boolean {
        return getFolderUri() != null
    }
    
    companion object {
        private const val PREFS_NAME = "splatman_folder_prefs"
        
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_LAST_SCAN = "last_scan_timestamp"
        private const val KEY_LAST_UPDATED = "last_updated_timestamp"
        private const val KEY_AUTO_SCAN = "auto_scan_enabled"
    }
}
