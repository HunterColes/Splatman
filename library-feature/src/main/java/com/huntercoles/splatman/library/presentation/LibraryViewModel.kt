package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** How long "Deleted n" offers Undo before the folders really go. */
const val UNDO_WINDOW_MS = 5_000L

/** What the user is doing on the Library besides looking: selecting, confirming, waiting on Undo. */
private data class Controls(
    val selected: Set<String> = emptySet(),
    val confirm: Set<String>? = null,
    val pending: Set<String> = emptySet(),
)

/**
 * The Library's state: the scans newest first (Loading until the repository's first list, then Empty or the cards),
 * selection mode, the delete dialog and the Undo window. Deleting hides the scans at once and deletes their folders
 * only when the window closes, so Undo needs nothing back from disk. Building scans carry their [buildProgress]
 * percent; [scanSupport] answering false dims the Scan FAB, whose tap then explains instead of opening Capture.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    sampleRepository: SampleRepository,
    private val buildProgress: BuildProgress,
    scanSupport: ScanSupport,
    private val sizeText: SizeText,
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())
    private val scanAvailable = MutableStateFlow(true)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    private var commitJob: Job? = null

    /** What the screen does once: open Capture, or show why it cannot. */
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    private val samples = flow { emit(sampleRepository.samples()) }.onStart { emit(emptyList()) }

    /** The scans with the build percent of each PROCESSING one. */
    private val scansWithPercent: Flow<Pair<List<Scan>, Map<String, Int?>>> =
        scanRepository.scans().flatMapLatest { scans ->
            val building = scans.filter { it.status == ScanStatus.PROCESSING }.map { it.id }
            if (building.isEmpty()) {
                flowOf(scans to emptyMap())
            } else {
                combine(building.map(::percentOf)) { pairs -> scans to pairs.toMap() }
            }
        }

    val uiState: StateFlow<LibraryUiState> =
        combine(scansWithPercent, samples, controls, scanAvailable) { (scans, percents), samples, c, available ->
            reduce(scans, percents, c).copy(samples = samples, scanAvailable = available)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    init {
        viewModelScope.launch { scanAvailable.value = scanSupport.canScan() }
        viewModelScope.launch {
            try {
                scanRepository.sync()
            } catch (e: IOException) {
                Timber.w(e, "Scan folder sync failed")
            }
        }
    }

    fun onIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.LongPress -> controls.update { it.copy(selected = it.selected + intent.id) }
            is LibraryIntent.Toggle -> controls.update { it.copy(selected = it.selected.toggle(intent.id)) }
            LibraryIntent.ExitSelection -> controls.update { it.copy(selected = emptySet()) }
            is LibraryIntent.RequestDelete -> controls.update { c ->
                val ids = intent.ids ?: c.selected
                if (ids.isEmpty()) c else c.copy(confirm = ids)
            }
            LibraryIntent.CancelDelete -> controls.update { it.copy(confirm = null) }
            LibraryIntent.ConfirmDelete -> confirmDelete()
            LibraryIntent.Undo -> {
                commitJob?.cancel()
                commitJob = null
                controls.update { it.copy(pending = emptySet()) }
            }
            LibraryIntent.CommitDelete -> commitNow()
            is LibraryIntent.Rename -> rename(intent.id, intent.name)
            LibraryIntent.Scan ->
                eventChannel.trySend(if (scanAvailable.value) LibraryEvent.OpenScan else LibraryEvent.ArUnsupported)
        }
    }

    private fun percentOf(id: String): Flow<Pair<String, Int?>> =
        buildProgress.percent(id).onStart { emit(null) }.distinctUntilChanged().map { id to it?.coerceIn(0, PERCENT) }

    private fun reduce(scans: List<Scan>, percents: Map<String, Int?>, c: Controls): LibraryUiState {
        val visible = scans.filter { it.id !in c.pending }
        val ids = visible.mapTo(HashSet()) { it.id }
        val selected = c.selected.filterTo(LinkedHashSet()) { it in ids }
        val content = if (visible.isEmpty()) {
            LibraryContent.Empty
        } else {
            LibraryContent.Cards(
                visible.map {
                    it.toCard(
                        selected = it.id in selected,
                        percent = percents[it.id],
                        sizeText = sizeText.format(it.sizeBytes),
                    )
                },
            )
        }
        return LibraryUiState(
            content = content,
            selectedIds = selected,
            confirmDelete = c.confirm,
            undo = c.pending.takeIf { it.isNotEmpty() }?.let(::PendingDelete),
        )
    }

    private fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { scanRepository.rename(id, name.trim()) }
    }

    private fun confirmDelete() {
        val ids = controls.value.confirm ?: return
        // A second delete inside the first one's Undo window commits the first.
        commitNow()
        controls.update { it.copy(selected = emptySet(), confirm = null, pending = it.pending + ids) }
        commitJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commit(ids)
        }
    }

    private fun commitNow() {
        val ids = controls.value.pending
        if (ids.isEmpty()) return
        commitJob?.cancel()
        commitJob = viewModelScope.launch { commit(ids) }
    }

    private suspend fun commit(ids: Set<String>) {
        withContext(NonCancellable) {
            ids.forEach { id ->
                try {
                    scanRepository.delete(id)
                } catch (e: IOException) {
                    Timber.w(e, "Deleting scan %s failed", id)
                }
            }
            controls.update { it.copy(pending = it.pending - ids) }
        }
    }

    private companion object {
        const val PERCENT = 100

        fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
    }
}
