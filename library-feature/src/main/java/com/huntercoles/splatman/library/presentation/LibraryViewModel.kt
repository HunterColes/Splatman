package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** How long "Deleted n" offers Undo before the folders really go. */
const val UNDO_WINDOW_MS = 5_000L

/** What the user is doing on the Library besides looking: selecting, confirming, sharing. */
private data class Controls(
    val selected: Set<String> = emptySet(),
    val confirm: Set<String>? = null,
    val sharing: Boolean = false,
)

/**
 * The Library's state: the scans newest first (Loading until the repository's first list, then Empty or the cards),
 * selection mode, the delete dialog and the Undo window. Deletes go through [pendingDeletes], shared with the Viewer:
 * the scans hide at once and their folders go only when the window closes, so a Viewer delete pops back to the
 * Library and its snackbar offers Undo here. Building scans carry their [buildProgress]
 * percent; [scanSupport] answering false dims the Scan FAB, whose tap then explains instead of opening Capture.
 * Share has [scanSharer] write the built scans' files and hands their `content://` URIs to the screen.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    sampleRepository: SampleRepository,
    private val buildProgress: BuildProgress,
    scanSupport: ScanSupport,
    private val sizeText: SizeText,
    private val scanSharer: ScanSharer,
    private val pendingDeletes: PendingDeletes,
) : ViewModel() {

    private val controls = MutableStateFlow(Controls())
    private val scanAvailable = MutableStateFlow(true)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    private var samplesById: Map<String, Sample> = emptyMap()

    /** What the screen does once: open Capture, or show why it cannot. */
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    private val samples = flow { emit(sampleRepository.samples()) }
        .onStart { emit(emptyList()) }
        .onEach { list -> samplesById = list.associateBy { it.id } }

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
        combine(scansWithPercent, samples, controls, scanAvailable, pendingDeletes.state) {
                (scans, percents), samples, c, available, pending ->
            reduce(scans, percents, c, pending).copy(samples = samples, scanAvailable = available)
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
            LibraryIntent.Undo -> pendingDeletes.undo()
            LibraryIntent.CommitDelete -> pendingDeletes.commitNow()
            is LibraryIntent.Rename -> rename(intent.id, intent.name)
            LibraryIntent.Scan ->
                eventChannel.trySend(if (scanAvailable.value) LibraryEvent.OpenScan else LibraryEvent.ArUnsupported)
            is LibraryIntent.Share -> share(intent.ids ?: controls.value.selected)
        }
    }

    private fun percentOf(id: String): Flow<Pair<String, Int?>> =
        buildProgress.percent(id).onStart { emit(null) }.distinctUntilChanged().map { id to it?.coerceIn(0, PERCENT) }

    private fun reduce(
        scans: List<Scan>,
        percents: Map<String, Int?>,
        c: Controls,
        pending: PendingDeletes.State,
    ): LibraryUiState {
        val visible = scans.filter { it.id !in pending.hidden }
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
            undo = pending.offer,
            sharing = c.sharing,
        )
    }

    /**
     * Shares [ids] in the order given: only built scans (and bundled samples) are written; a scan deleted since it
     * was selected ends in [LibraryEvent.ShareGone], anything but `content://` URIs back in [LibraryEvent.ShareFailed].
     */
    private fun share(ids: Set<String>) {
        if (ids.isEmpty() || controls.value.sharing) return
        controls.update { it.copy(sharing = true) }
        viewModelScope.launch {
            try {
                shareEvent(ids.toList())?.let { eventChannel.send(it) }
            } finally {
                controls.update { it.copy(sharing = false) }
            }
        }
    }

    private suspend fun shareEvent(ids: List<String>): LibraryEvent? {
        val scans = ids.map { id ->
            samplesById[id]?.let { return@map ShareScan(it.id, it.name) }
            // Deleted, or waiting out its Undo window: gone as far as the user can see.
            val hidden = id in pendingDeletes.state.value.hidden
            val scan = scanRepository.get(id)?.takeUnless { hidden } ?: return LibraryEvent.ShareGone
            // Share is off for a scan with no splat; a stale tap shares nothing.
            if (scan.status != ScanStatus.READY) return null
            ShareScan(scan.id, scan.name)
        }
        val files = try {
            scanSharer.prepare(scans)
        } catch (e: IOException) {
            Timber.w(e, "Preparing %d share files failed", scans.size)
            null
        }
        return when {
            files == null -> if (ids.any { it !in samplesById && scanRepository.get(it) == null }) {
                LibraryEvent.ShareGone
            } else {
                LibraryEvent.ShareFailed
            }
            !files.allContent -> LibraryEvent.ShareFailed
            else -> LibraryEvent.Share(files)
        }
    }

    private fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { scanRepository.rename(id, name.trim()) }
    }

    private fun confirmDelete() {
        val ids = controls.value.confirm ?: return
        controls.update { it.copy(selected = emptySet(), confirm = null) }
        pendingDeletes.defer(ids)
    }

    private companion object {
        const val PERCENT = 100

        fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
    }
}
