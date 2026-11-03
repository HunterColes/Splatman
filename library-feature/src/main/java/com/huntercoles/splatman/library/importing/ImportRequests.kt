package com.huntercoles.splatman.library.importing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files other apps hand to Splatman ("Open with" on a `.ply` or `.splat`): the activity [offer]s the `content://` URI,
 * the Library's import flow [take]s it once it is on screen. Only the newest unhandled request is kept.
 */
@Singleton
class ImportRequests @Inject constructor() {
    private val pending = MutableStateFlow<String?>(null)

    val requests: StateFlow<String?> get() = pending

    fun offer(uri: String) {
        pending.value = uri
    }

    /** Clears [uri] when it is still the pending request; true when this call took it. */
    fun take(uri: String): Boolean = pending.compareAndSet(uri, null)
}
