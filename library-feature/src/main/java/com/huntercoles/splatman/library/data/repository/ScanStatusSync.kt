package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the library's rows in step with the processing runner. Events are applied one at a time, in the order they
 * were posted, on [scope]: a run's start never lands after its end.
 */
class ScanStatusSync(private val scans: ScanRepository, scope: CoroutineScope) {

    private sealed interface Event {
        val id: String

        data class Started(override val id: String) : Event

        data class Finished(override val id: String, val failed: Boolean) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    apply(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "Library status update failed for %s", event.id)
                }
            }
        }
    }

    /** Scan [id]'s run started: its row (adopted first if the scan is new) reads PROCESSING. */
    fun started(id: String) {
        events.trySend(Event.Started(id))
    }

    /**
     * Scan [id]'s run ended. [failed]: the row reads FAILED; else it is read from the folder again (READY with the
     * splat written, or with the seed splat a cancel keeps; CAPTURED when a cancel left no splat).
     */
    fun finished(id: String, failed: Boolean) {
        events.trySend(Event.Finished(id, failed))
    }

    /**
     * After the app restarts: a row still PROCESSING whose run is not going ([isRunning], by folder) was killed
     * mid-way, so it reads FAILED (Processing offers Retry) instead of spinning for ever.
     */
    suspend fun recover(isRunning: (folder: String) -> Boolean) {
        scans.scans().first()
            .filter { it.status == ScanStatus.PROCESSING && !isRunning(it.folder) }
            .forEach { scans.updateStatus(it.id, ScanStatus.FAILED) }
    }

    private suspend fun apply(event: Event) {
        when (event) {
            is Event.Started -> {
                if (scans.get(event.id) == null) scans.refresh(event.id)
                scans.updateStatus(event.id, ScanStatus.PROCESSING)
            }
            is Event.Finished -> {
                scans.refresh(event.id)
                if (event.failed) scans.updateStatus(event.id, ScanStatus.FAILED)
            }
        }
    }
}
