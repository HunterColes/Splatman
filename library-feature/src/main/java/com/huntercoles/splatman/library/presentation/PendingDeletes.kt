package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.core.coroutines.MainImmediateScope
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans deleted with Undo, from the Library or the Viewer: [defer] hides them at once and deletes their folders and
 * rows only when [UNDO_WINDOW_MS] runs out, so Undo needs nothing back from disk. It outlives the screens (the Viewer
 * pops before the window closes); nothing is written before the window ends, so a process killed inside it keeps
 * every scan, and one killed while deleting leaves a row without a folder that the Library's sync drops.
 */
@Singleton
class PendingDeletes @Inject constructor(
    private val scans: ScanRepository,
    @MainImmediateScope private val scope: CoroutineScope,
) {

    /** [hidden]: every scan not to list (offered or being deleted); [offer]: the delete the snackbar can undo. */
    data class State(val hidden: Set<String> = emptySet(), val offer: PendingDelete? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var timer: Job? = null

    /** Hides [ids] and offers Undo; a delete still on offer is committed first, never dropped. */
    fun defer(ids: Set<String>) {
        if (ids.isEmpty()) return
        commitNow()
        val offer = PendingDelete(ids)
        _state.update { it.copy(hidden = it.hidden + ids, offer = offer) }
        timer = scope.launch {
            delay(UNDO_WINDOW_MS)
            commit(offer)
        }
    }

    /** Brings the offered scans back. */
    fun undo() {
        val offer = _state.value.offer ?: return
        timer?.cancel()
        timer = null
        _state.update { it.copy(hidden = it.hidden - offer.ids, offer = null) }
    }

    /** Deletes the offered scans now (the snackbar was dismissed, or another delete came). */
    fun commitNow() {
        val offer = _state.value.offer ?: return
        timer?.cancel()
        timer = null
        _state.update { it.copy(offer = null) }
        scope.launch { commit(offer) }
    }

    private suspend fun commit(offer: PendingDelete) {
        withContext(NonCancellable) {
            // Past this point Undo has nothing to bring back.
            _state.update { s -> s.copy(offer = s.offer.takeUnless { it == offer }) }
            offer.ids.forEach { id ->
                try {
                    scans.delete(id)
                } catch (e: IOException) {
                    Timber.w(e, "Deleting scan %s failed", id)
                }
            }
            _state.update { it.copy(hidden = it.hidden - offer.ids) }
        }
    }
}
