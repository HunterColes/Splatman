package com.huntercoles.splatman.library.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Library folder new scans land in (ADR 0013 point 8): set while a folder is open, null at the Library root.
 * `ScanRepositoryImpl` uses it only when the folder still exists.
 */
@Singleton
class LandingFolder @Inject constructor() {
    private val current = MutableStateFlow<String?>(null)

    val folderId: StateFlow<String?> = current.asStateFlow()

    fun set(id: String?) {
        current.value = id
    }
}
