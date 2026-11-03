package com.huntercoles.splatman.core.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Capture's remembered choices. The scan mode is stored as its key ("object" or "room"); core does not know ScanMode. */
@Singleton
class CapturePreferences(private val store: PreferenceStore) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(SharedPreferenceStore(context, FILE))

    private val _scanMode = MutableStateFlow(normalise(store.getString(SCAN_MODE_KEY)))

    /** The last scan mode key; "object" on first run or when the stored value is unknown. */
    val scanMode: StateFlow<String> = _scanMode.asStateFlow()

    fun setScanMode(key: String) {
        store.putString(SCAN_MODE_KEY, key)
        _scanMode.value = normalise(key)
    }

    private fun normalise(key: String?): String = if (key == ROOM) ROOM else OBJECT

    companion object {
        private const val FILE = "capture_prefs"
        private const val SCAN_MODE_KEY = "scan_mode"
        const val OBJECT = "object"
        const val ROOM = "room"
    }
}
