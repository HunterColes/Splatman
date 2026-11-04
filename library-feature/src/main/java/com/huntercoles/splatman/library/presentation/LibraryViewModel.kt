package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.library.data.prefs.LibraryPreferences
import com.huntercoles.splatman.library.domain.LandingFolder
import com.huntercoles.splatman.library.domain.LibrarySearch
import com.huntercoles.splatman.library.domain.LibrarySort
import com.huntercoles.splatman.library.domain.LibraryText
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.FolderRepository
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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

/** The SavedStateHandle key of the open folder: the property of `NavigationDestination.LibraryFolder`. */
const val FOLDER_ID_KEY = "folderId"

/** How many scan thumbnails a folder cell shows. */
private const val FOLDER_THUMBNAILS = 4

/** What the user is doing on the Library besides looking: selecting, confirming, sharing, searching. */
private data class Controls(
    val selected: Set<String> = emptySet(),
    val confirm: Set<String>? = null,
    val sharing: Boolean = false,
    val search: SearchState = SearchState(),
)

/** What the Library lists: the scans with their build percents, the folders and the remembered sort. */
private data class Listing(
    val scans: List<Scan>,
    val percents: Map<String, Int?>,
    val folders: List<LibraryFolder>,
    val sort: LibrarySort,
)

/**
 * The Library's state in one place, the root or a folder (ADR 0013): at the root the folders, then the loose scans,
 * inside a folder only its scans, both in the sort remembered by [libraryPreferences] (Loading until the first lists,
 * then Empty, FolderEmpty, NoMatch or the cards). Live search covers every scan and folder name at the root, the
 * folder's scans inside one. Filtering and sorting run on [defaultDispatcher]. Selection mode, the delete dialog and
 * the Undo window are per place. Deletes go through [pendingDeletes], shared with the Viewer: the scans hide at once
 * and their folders go only when the window closes, so a Viewer delete pops back to the Library and its snackbar
 * offers Undo here. Building scans carry their [buildProgress] percent; [scanSupport] answering false dims the Scan
 * FAB, whose tap then explains instead of opening Capture. Share has [scanSharer] write the built scans' files and
 * hands their `content://` URIs to the screen. A folder place sets [landingFolder] while it lives, so new scans land
 * in it, and emits [LibraryEvent.LeaveFolder] once the folder is gone.
 */
@HiltViewModel
@Suppress("LongParameterList") // one constructor seam per collaborator, as Hilt injects them
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scanRepository: ScanRepository,
    private val folderRepository: FolderRepository,
    private val libraryPreferences: LibraryPreferences,
    private val landingFolder: LandingFolder,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    sampleRepository: SampleRepository,
    private val buildProgress: BuildProgress,
    scanSupport: ScanSupport,
    private val sizeText: SizeText,
    private val scanSharer: ScanSharer,
    private val pendingDeletes: PendingDeletes,
) : ViewModel() {

    /**
     * The Library root with no folders and an in-memory sort (newest first), its grid built on the caller's thread:
     * for previews and screen goldens that predate folders.
     */
    constructor(
        scanRepository: ScanRepository,
        sampleRepository: SampleRepository,
        buildProgress: BuildProgress,
        scanSupport: ScanSupport,
        sizeText: SizeText,
        scanSharer: ScanSharer,
        pendingDeletes: PendingDeletes,
    ) : this(
        savedStateHandle = SavedStateHandle(),
        scanRepository = scanRepository,
        folderRepository = NoFolders,
        libraryPreferences = LibraryPreferences(MemoryStore()),
        landingFolder = LandingFolder(),
        defaultDispatcher = Dispatchers.Unconfined,
        sampleRepository = sampleRepository,
        buildProgress = buildProgress,
        scanSupport = scanSupport,
        sizeText = sizeText,
        scanSharer = scanSharer,
        pendingDeletes = pendingDeletes,
    )

    private val folderId: String? = savedStateHandle.get<String>(FOLDER_ID_KEY)
    private val controls = MutableStateFlow(Controls())
    private val scanAvailable = MutableStateFlow(true)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)

    @Volatile
    private var samplesById: Map<String, Sample> = emptyMap()

    /** The open folder's last known name, kept while it vanishes. */
    @Volatile
    private var folderName = ""

    /** What the screen does once: open Capture, show why it cannot, scroll to the top, leave a folder gone. */
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

    private val listing: Flow<Listing> =
        combine(scansWithPercent, folderRepository.folders(), libraryPreferences.sort) { (scans, percents), f, sort ->
            Listing(scans, percents, f, sort)
        }

    val uiState: StateFlow<LibraryUiState> =
        combine(listing, samples, controls, scanAvailable, pendingDeletes.state) { listing, samples, c, available, p ->
            reduce(listing, c, p).copy(samples = samples, scanAvailable = available)
        }
            .flowOn(defaultDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState(place = place()))

    init {
        viewModelScope.launch { scanAvailable.value = scanSupport.canScan() }
        viewModelScope.launch {
            try {
                scanRepository.sync()
            } catch (e: IOException) {
                Timber.w(e, "Scan folder sync failed")
            }
        }
        folderId?.let { id ->
            landingFolder.set(id)
            viewModelScope.launch {
                folderRepository.folders().first { list -> list.none { it.id == id } }
                eventChannel.send(LibraryEvent.LeaveFolder)
            }
        }
    }

    override fun onCleared() {
        if (folderId != null && landingFolder.folderId.value == folderId) landingFolder.set(null)
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
            is LibraryIntent.SetSort -> setSort(intent.sort)
            LibraryIntent.OpenSearch -> controls.update { it.copy(search = it.search.copy(open = true)) }
            is LibraryIntent.Query -> query(intent.text)
            LibraryIntent.CloseSearch -> controls.update { it.copy(selected = emptySet(), search = SearchState()) }
        }
    }

    private fun setSort(sort: LibrarySort) {
        if (sort == libraryPreferences.sort.value) return
        libraryPreferences.setSort(sort)
        eventChannel.trySend(LibraryEvent.ScrollToTop)
    }

    private fun query(text: String) {
        if (text == controls.value.search.query) return
        controls.update { it.copy(search = it.search.copy(query = text)) }
        eventChannel.trySend(LibraryEvent.ScrollToTop)
    }

    private fun place(): LibraryPlace = folderId?.let { LibraryPlace.Folder(it, folderName) } ?: LibraryPlace.Root

    private fun percentOf(id: String): Flow<Pair<String, Int?>> =
        buildProgress.percent(id).onStart { emit(null) }.distinctUntilChanged().map { id to it?.coerceIn(0, PERCENT) }

    private fun reduce(listing: Listing, c: Controls, pending: PendingDeletes.State): LibraryUiState {
        val live = listing.scans.filter { it.id !in pending.hidden }
        val namesById = listing.folders.associate { it.id to it.name }
        val query = if (c.search.open) c.search.query else ""
        val searching = LibraryText.key(query).isNotEmpty()
        val byScan = listing.sort.comparator<Scan>(
            name = { it.name },
            createdAtMs = { it.createdAtMs },
            sizeBytes = { if (it.status == ScanStatus.READY) it.sizeBytes else null },
            id = { it.id },
        )
        val inFolder = live.filter { it.folderId in namesById }.groupBy { it.folderId }
        val scope = when {
            folderId != null -> live.filter { it.folderId == folderId }
            searching -> live
            else -> live.filter { it.folderId !in namesById }
        }
        val shown = LibrarySearch.filter(scope, query) { it.name }.sortedWith(byScan)
        val folders = if (folderId != null) {
            namesById[folderId]?.let { folderName = it }
            emptyList()
        } else {
            folderCards(LibrarySearch.filter(listing.folders, query) { it.name }, inFolder, listing.sort, byScan)
        }
        val ids = shown.mapTo(HashSet()) { it.id }
        val selected = c.selected.filterTo(LinkedHashSet()) { it in ids }
        // A selected scan that stops showing (no longer matches, moved, deleted) leaves the selection for good.
        val dropped = c.selected - selected
        if (dropped.isNotEmpty()) controls.update { it.copy(selected = it.selected - dropped) }
        val content = when {
            folderId == null && live.isEmpty() && listing.folders.isEmpty() -> LibraryContent.Empty
            folderId != null && scope.isEmpty() -> LibraryContent.FolderEmpty
            searching && shown.isEmpty() && folders.isEmpty() -> LibraryContent.NoMatch(query)
            else -> LibraryContent.Cards(
                shown.map {
                    it.toCard(
                        selected = it.id in selected,
                        percent = listing.percents[it.id],
                        sizeText = sizeText.format(it.sizeBytes),
                        inFolderName = if (searching && folderId == null) namesById[it.folderId] else null,
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
            place = place(),
            sort = listing.sort,
            search = c.search,
            folders = folders,
        )
    }

    /** The cells of [folders] in [sort]: their scans counted, sizes summed, the first thumbnails in [byScan]. */
    private fun folderCards(
        folders: List<LibraryFolder>,
        scansByFolder: Map<String?, List<Scan>>,
        sort: LibrarySort,
        byScan: Comparator<Scan>,
    ): List<FolderCard> {
        val sizes = folders.associate { f -> f.id to scansByFolder[f.id].orEmpty().sumOf { it.sizeBytes } }
        val byFolder = sort.comparator<LibraryFolder>(
            name = { it.name },
            createdAtMs = { it.createdAtMs },
            sizeBytes = { sizes.getValue(it.id) },
            id = { it.id },
        )
        return folders.sortedWith(byFolder).map { f ->
            val scans = scansByFolder[f.id].orEmpty()
            FolderCard(
                id = f.id,
                name = f.name,
                scanCount = scans.size,
                sizeBytes = sizes.getValue(f.id),
                thumbnails = scans.sortedWith(byScan).mapNotNull { it.thumbnail }.take(FOLDER_THUMBNAILS),
            )
        }
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

/** No folders at all, for the folderless constructor: the root lists every scan. */
private object NoFolders : FolderRepository {
    override fun folders(): Flow<List<LibraryFolder>> = flowOf(emptyList())

    override suspend fun create(name: String): LibraryFolder = throw UnsupportedOperationException("no folders")

    override suspend fun rename(id: String, name: String): LibraryFolder = throw NoSuchElementException(id)

    override suspend fun delete(id: String): DeletedFolder = throw NoSuchElementException(id)

    override suspend fun restore(deleted: DeletedFolder) = Unit

    override suspend fun move(scanIds: Collection<String>, folderId: String?): Map<String, String?> =
        if (folderId == null) scanIds.associateWith { null } else throw NoSuchElementException(folderId)

    override suspend fun restorePlaces(places: Map<String, String?>) = Unit
}

/** Preferences that live as long as the ViewModel, for the folderless constructor. */
private class MemoryStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
