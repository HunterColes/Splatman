package com.huntercoles.splatman.library.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.library.data.prefs.LibraryPreferences
import com.huntercoles.splatman.library.domain.LandingFolder
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import com.huntercoles.splatman.library.domain.repository.FolderRepository
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * #312: the Library at the root or inside a folder (ADR 0013, docs/design/screens/library.md "Search, sort and
 * folders"), over a fake FolderRepository, the fake scans and the real [LibraryPreferences] on an in-memory store.
 */
abstract class LibraryPlacesHarness {

    protected val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    protected val repository = ListScanRepository()
    protected val folders = FakeFolderRepository()
    protected val store = MemoryPreferenceStore()
    protected val preferences = LibraryPreferences(store)
    protected val landing = LandingFolder()
    protected val progress = mutableMapOf<String, MutableSharedFlow<Int?>>()
    private var deletes: PendingDeletes? = null

    /** A scan with everything the sort and search look at; [folderId] null is the Library root. */
    protected fun item(
        id: String,
        name: String = "Scan $id",
        createdAtMs: Long = 1_000,
        sizeBytes: Long = 0,
        folderId: String? = null,
        status: ScanStatus = ScanStatus.READY,
        thumbnail: ScanThumbnail? = null,
    ) = Scan(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        status = status,
        splatCount = 10,
        keyframeCount = 3,
        folder = id,
        sizeBytes = sizeBytes,
        thumbnail = thumbnail,
        folderId = folderId,
    )

    /**
     * The Library at the root, or inside folder [folderId] (SavedStateHandle key `folderId`, the property of
     * `NavigationDestination.LibraryFolder`). Filtering and sorting run on [defaultDispatcher].
     */
    protected fun TestScope.viewModel(
        folderId: String? = null,
        defaultDispatcher: CoroutineDispatcher = dispatcher,
    ): LibraryViewModel {
        val pending = deletes ?: PendingDeletes(repository, backgroundScope).also { deletes = it }
        val vm = LibraryViewModel(
            savedStateHandle = SavedStateHandle(if (folderId == null) emptyMap() else mapOf("folderId" to folderId)),
            scanRepository = repository,
            folderRepository = folders,
            libraryPreferences = preferences,
            landingFolder = landing,
            defaultDispatcher = defaultDispatcher,
            sampleRepository = SampleRepository { emptyList() },
            buildProgress = BuildProgress { id -> progress.getOrPut(id) { MutableSharedFlow(replay = 1) } },
            scanSupport = ScanSupport { true },
            sizeText = SizeText { bytes -> "$bytes B" },
            scanSharer = ScanSharer { null },
            pendingDeletes = pending,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    protected fun LibraryViewModel.cards(): List<LibraryCard> = (uiState.value.content as LibraryContent.Cards).cards

    protected fun LibraryViewModel.folderIds(): List<String> = uiState.value.folders.map { it.id }

    protected fun LibraryViewModel.search(query: String) {
        onIntent(LibraryIntent.OpenSearch)
        onIntent(LibraryIntent.Query(query))
    }

    protected fun TestScope.collectEvents(vm: LibraryViewModel): MutableList<LibraryEvent> {
        val events = mutableListOf<LibraryEvent>()
        backgroundScope.launch { vm.events.toList(events) }
        runCurrent()
        return events
    }

    /** Ends [vm] the way leaving its screen does: its store is cleared, so onCleared runs. */
    protected fun clear(vm: LibraryViewModel) {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        ViewModelProvider(store, factory)[LibraryViewModel::class.java]
        store.clear()
    }

    /**
     * Two folders and three loose scans whose order differs per sort. F1 "Beta" (made 3 000, one scan of 60 B),
     * F2 "alpha" (made 1 000, two scans of 20 + 30 B). Loose: s1 "Scan 10" (2 000, 500 B), s2 "Scan 2" (4 000,
     * 100 B), s3 "zeta" (5 000, CAPTURED: unsized however big its folder is).
     */
    protected fun sortFixture() {
        folders.emit(listOf(LibraryFolder("F2", "alpha", createdAtMs = 1_000), LibraryFolder("F1", "Beta", 3_000)))
        repository.emit(
            listOf(
                item("f2a", createdAtMs = 500, sizeBytes = 20, folderId = "F2"),
                item("s1", "Scan 10", createdAtMs = 2_000, sizeBytes = 500),
                item("f1a", createdAtMs = 1_500, sizeBytes = 60, folderId = "F1"),
                item("s3", "zeta", createdAtMs = 5_000, sizeBytes = 1_000, status = ScanStatus.CAPTURED),
                item("f2b", createdAtMs = 1_000, sizeBytes = 30, folderId = "F2"),
                item("s2", "Scan 2", createdAtMs = 4_000, sizeBytes = 100),
            ),
        )
    }

    /**
     * Folders G "Garden" (1 000) and D "Desk stuff" (2 000). Scans: dp "Desk plant" in G (3 000), dl "desk lamp"
     * loose (2 000), dd "Desk drawer" in D (1 000), ch "Chair" loose (500), ro "Rose" in G (400).
     */
    protected fun searchFixture() {
        folders.emit(listOf(LibraryFolder("D", "Desk stuff", 2_000), LibraryFolder("G", "Garden", 1_000)))
        repository.emit(
            listOf(
                item("dp", "Desk plant", createdAtMs = 3_000, folderId = "G"),
                item("dl", "desk lamp", createdAtMs = 2_000),
                item("dd", "Desk drawer", createdAtMs = 1_000, folderId = "D"),
                item("ch", "Chair", createdAtMs = 500),
                item("ro", "Rose", createdAtMs = 400, folderId = "G"),
            ),
        )
    }
}

/** In-memory [PreferenceStore]: a new [LibraryPreferences] over it reads what the last one wrote. */
class MemoryPreferenceStore : PreferenceStore {
    val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/** Folders as rows only: [emit] sets the list; [delete] drops one (a test moves its scans itself). */
class FakeFolderRepository : FolderRepository {
    private val list = MutableStateFlow<List<LibraryFolder>>(emptyList())

    fun emit(folders: List<LibraryFolder>) {
        list.value = folders
    }

    override fun folders(): Flow<List<LibraryFolder>> = list

    override suspend fun create(name: String): LibraryFolder = error("not used")

    override suspend fun rename(id: String, name: String): LibraryFolder = error("not used")

    override suspend fun delete(id: String): DeletedFolder {
        val folder = list.value.first { it.id == id }
        list.value = list.value - folder
        return DeletedFolder(folder, emptyList())
    }

    override suspend fun restore(deleted: DeletedFolder) = error("not used")

    override suspend fun move(scanIds: Collection<String>, folderId: String?): Map<String, String?> =
        error("not used")

    override suspend fun restorePlaces(places: Map<String, String?>) = error("not used")
}

/** The scans as a list the test sets with [emit]; nothing until the first one. */
class ListScanRepository : ScanRepository {
    private val lists = MutableStateFlow<List<Scan>?>(null)

    fun emit(scans: List<Scan>) {
        lists.value = scans
    }

    override fun scans(): Flow<List<Scan>> = lists.filterNotNull()

    override suspend fun get(id: String): Scan? = lists.value?.firstOrNull { it.id == id }

    override suspend fun create(name: String, createdAtMs: Long): Scan = error("not used")

    override suspend fun import(
        name: String,
        createdAtMs: Long,
        splatCount: Int,
        writeSplat: (java.io.OutputStream) -> Unit,
    ): Scan = error("not used")

    override suspend fun updateStatus(id: String, status: ScanStatus, splatCount: Int?, keyframeCount: Int?) =
        error("not used")

    override suspend fun rename(id: String, name: String) = Unit

    override suspend fun delete(id: String) {
        lists.value = lists.value?.filterNot { it.id == id }
    }

    override suspend fun sync() = Unit

    override suspend fun refresh(id: String) = Unit
}
