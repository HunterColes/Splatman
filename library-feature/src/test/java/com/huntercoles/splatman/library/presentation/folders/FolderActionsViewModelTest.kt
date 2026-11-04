package com.huntercoles.splatman.library.presentation.folders

import androidx.lifecycle.SavedStateHandle
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.library.domain.LibraryText
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.FolderNameTaken
import com.huntercoles.splatman.library.domain.repository.FolderRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.io.OutputStream

/**
 * #313: [FolderActionsViewModel] (docs/design/screens/library.md "Folders: create, rename, move, delete", ADR 0013
 * points 6-9) over a fake [FolderRepository] that keeps the real one's name rules and a list of scans.
 *
 * The API these tests pin (FolderActionsContract.kt):
 * - `FolderActionsViewModel(savedStateHandle, folderRepository, scanRepository)`; SavedStateHandle key `folderId`
 *   is the open folder (absent = the Library root), as on `NavigationDestination.LibraryFolder`.
 * - `uiState: StateFlow<FolderActionsUiState>` with `dialog: NameDialog?`, `deleteConfirm: DeleteConfirm?`,
 *   `move: MoveSheet?`; `events: Flow<FolderActionsEvent>`; `onIntent(FolderActionsIntent)`.
 * - `NameDialog(mode: NameDialogMode, text, error: NameError, saving)` with `canConfirm`; `NameDialogMode.New`,
 *   `Rename(folderId)`, `NewForMove(scanIds)`; `NameError.None|Blank|Taken|TooLong`.
 * - `DeleteConfirm(folderId, name, scanCount)`; `MoveSheet(scanIds, targets: List<MoveTarget>)`;
 *   `MoveTarget(folderId: String?, name: String?, scanCount, hereNow)`: first the Library (folderId and name null),
 *   then every folder in NAME order.
 * - Events `Moved(count)`, `FolderDeleted(name)`, `MoveError`, `FolderError`, `LeftFolder(folderId)`.
 * - Intents `NewFolder`, `RenameFolder(folderId)`, `EditName(text)`, `ConfirmName`, `CancelName`,
 *   `AskDelete(folderId)`, `ConfirmDelete`, `CancelDelete`, `OpenMove(scanIds)`, `NewFolderForMove`,
 *   `MoveTo(folderId: String?)`, `CancelMove`, `Undo` (undoes the last Moved or FolderDeleted).
 */
class FolderActionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val scans = ListScanRepository()
    private val folders = FakeFolderRepository(scans)

    private fun scan(id: String, folderId: String? = null, status: ScanStatus = ScanStatus.READY) =
        Scan(id, "Scan $id", createdAtMs = 1_000, status = status, splatCount = 10, keyframeCount = 3, folder = id,
            folderId = folderId)

    private fun TestScope.viewModel(openFolderId: String? = null): FolderActionsViewModel {
        val vm = FolderActionsViewModel(
            savedStateHandle = SavedStateHandle(if (openFolderId == null) emptyMap() else mapOf("folderId" to openFolderId)),
            folderRepository = folders,
            scanRepository = scans,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun TestScope.events(vm: FolderActionsViewModel): MutableList<FolderActionsEvent> {
        val out = mutableListOf<FolderActionsEvent>()
        backgroundScope.launch { vm.events.toList(out) }
        runCurrent()
        return out
    }

    private fun TestScope.send(vm: FolderActionsViewModel, vararg intents: FolderActionsIntent) {
        intents.forEach {
            vm.onIntent(it)
            runCurrent()
        }
    }

    private fun FolderActionsViewModel.dialog(): NameDialog = uiState.value.dialog!!

    /** Folders "Garden" (G, two scans), "Desk stuff" (D, one scan) and "alpha" (A, empty); two loose scans. */
    private fun fixture() {
        folders.set(
            LibraryFolder("G", "Garden", 3_000),
            LibraryFolder("D", "Desk stuff", 2_000),
            LibraryFolder("A", "alpha", 1_000),
        )
        scans.set(scan("g1", "G"), scan("g2", "G"), scan("d1", "D"), scan("r1"), scan("r2"))
    }

    // Acceptance 1: the name dialog

    @Test
    fun `A1 create stays disabled while the trimmed name is blank`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()

        send(vm, FolderActionsIntent.NewFolder)
        assertEquals(NameDialogMode.New, vm.dialog().mode)
        assertEquals("", vm.dialog().text)
        assertFalse(vm.dialog().canConfirm)

        send(vm, FolderActionsIntent.EditName("   \n"))
        assertFalse(vm.dialog().canConfirm)
        send(vm, FolderActionsIntent.ConfirmName)
        assertEquals(emptyList<String>(), folders.created)
        assertEquals("   \n", vm.dialog().text)

        send(vm, FolderActionsIntent.EditName("  Plants "))
        assertTrue(vm.dialog().canConfirm)
        send(vm, FolderActionsIntent.ConfirmName)
        assertEquals(listOf("Plants"), folders.current().filter { it.name == "Plants" }.map { it.name })
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `A1 a taken name keeps the dialog open with its error and the text`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName("GARDÉN"), FolderActionsIntent.ConfirmName)

        assertEquals(NameError.Taken, vm.dialog().error)
        assertEquals("GARDÉN", vm.dialog().text)
        assertFalse(vm.dialog().saving)
        assertEquals(3, folders.current().size)
        assertEquals(emptyList<FolderActionsEvent>(), events)

        send(vm, FolderActionsIntent.EditName("Gardens"))
        assertEquals(NameError.None, vm.dialog().error)
    }

    @Test
    fun `A1 a name over 60 characters keeps the dialog open with TooLong and the text`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val long = "x".repeat(61)

        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName(long), FolderActionsIntent.ConfirmName)

        assertEquals(NameError.TooLong, vm.dialog().error)
        assertEquals(long, vm.dialog().text)
        assertEquals(3, folders.current().size)
    }

    @Test
    fun `A1 rename is prefilled and its own name is fine`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()

        send(vm, FolderActionsIntent.RenameFolder("G"))
        assertEquals(NameDialogMode.Rename("G"), vm.dialog().mode)
        assertEquals("Garden", vm.dialog().text)
        assertTrue(vm.dialog().canConfirm)

        send(vm, FolderActionsIntent.EditName("garden"), FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)
        assertEquals("garden", folders.current().single { it.id == "G" }.name)

        send(vm, FolderActionsIntent.RenameFolder("G"), FolderActionsIntent.EditName("Desk Stuff"))
        send(vm, FolderActionsIntent.ConfirmName)
        assertEquals(NameError.Taken, vm.dialog().error)
        assertEquals("garden", folders.current().single { it.id == "G" }.name)

        send(vm, FolderActionsIntent.CancelName)
        assertNull(vm.uiState.value.dialog)
    }

    // Acceptance 2: New folder from the Move sheet

    @Test
    fun `A2 NewForMove creates the folder and moves the scans in one action with one Moved`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.OpenMove(listOf("r1", "g1")), FolderActionsIntent.NewFolderForMove)
        assertEquals(NameDialogMode.NewForMove(listOf("r1", "g1")), vm.dialog().mode)

        send(vm, FolderActionsIntent.EditName("Trip"), FolderActionsIntent.ConfirmName)

        val trip = folders.current().single { it.name == "Trip" }
        assertEquals(trip.id, scans.place("r1"))
        assertEquals(trip.id, scans.place("g1"))
        assertEquals(listOf(FolderActionsEvent.Moved(2)), events)
        assertNull(vm.uiState.value.dialog)
        assertNull(vm.uiState.value.move)
    }

    // Acceptance 3: hereNow

    @Test
    fun `A3 a Move target is hereNow only when every selected scan is already there`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()

        send(vm, FolderActionsIntent.OpenMove(listOf("g1", "g2")))
        val sheet = vm.uiState.value.move!!
        assertEquals(listOf("g1", "g2"), sheet.scanIds)
        assertEquals(listOf(null, "A", "D", "G"), sheet.targets.map { it.folderId })
        assertEquals(listOf(null, "alpha", "Desk stuff", "Garden"), sheet.targets.map { it.name })
        assertEquals(listOf(2, 0, 1, 2), sheet.targets.map { it.scanCount })
        assertEquals(listOf(false, false, false, true), sheet.targets.map { it.hereNow })

        send(vm, FolderActionsIntent.CancelMove)
        assertNull(vm.uiState.value.move)

        send(vm, FolderActionsIntent.OpenMove(listOf("r1", "r2")))
        assertEquals(listOf(true, false, false, false), vm.uiState.value.move!!.targets.map { it.hereNow })
    }

    // Acceptance 4: Undo

    @Test
    fun `A4 Undo after Moved calls restorePlaces with the returned map`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.OpenMove(listOf("g1", "r1")), FolderActionsIntent.MoveTo("D"))
        assertEquals(listOf(FolderActionsEvent.Moved(2)), events)
        assertNull(vm.uiState.value.move)
        assertEquals("D", scans.place("g1"))

        send(vm, FolderActionsIntent.Undo)

        assertEquals(listOf(mapOf("g1" to "G", "r1" to null)), folders.restoredPlaces)
        assertEquals("G", scans.place("g1"))
        assertNull(scans.place("r1"))
    }

    @Test
    fun `A4 Undo after FolderDeleted calls restore with the DeletedFolder`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.AskDelete("G"))
        assertEquals(DeleteConfirm("G", "Garden", 2), vm.uiState.value.deleteConfirm)

        send(vm, FolderActionsIntent.ConfirmDelete)
        assertNull(vm.uiState.value.deleteConfirm)
        assertEquals(listOf(FolderActionsEvent.FolderDeleted("Garden")), events)
        assertEquals(listOf("A", "D"), folders.current().map { it.id }.sorted())

        send(vm, FolderActionsIntent.Undo)

        val deleted = folders.deletedResults.single()
        assertEquals(listOf(deleted), folders.restored)
        assertEquals(LibraryFolder("G", "Garden", 3_000), folders.current().single { it.id == "G" })
        assertEquals("G", scans.place("g1"))
    }

    @Test
    fun `A4 deleting the open folder emits LeftFolder`() = runTest(dispatcher) {
        fixture()
        val inside = viewModel(openFolderId = "D")
        val events = events(inside)

        send(inside, FolderActionsIntent.AskDelete("D"), FolderActionsIntent.ConfirmDelete)

        assertTrue(FolderActionsEvent.LeftFolder("D") in events)
        assertTrue(FolderActionsEvent.FolderDeleted("Desk stuff") in events)

        val atRoot = viewModel()
        val rootEvents = events(atRoot)
        send(atRoot, FolderActionsIntent.AskDelete("A"), FolderActionsIntent.ConfirmDelete)
        assertEquals(listOf(FolderActionsEvent.FolderDeleted("alpha")), rootEvents)
    }

    // Acceptance 5: repository failures

    @Test
    fun `A5 a failed move emits MoveError and nothing moves`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)
        folders.failing = true

        send(vm, FolderActionsIntent.OpenMove(listOf("g1", "r1")), FolderActionsIntent.MoveTo("D"))

        assertEquals(listOf(FolderActionsEvent.MoveError), events)
        assertEquals("G", scans.place("g1"))
        assertNull(scans.place("r1"))

        send(vm, FolderActionsIntent.Undo)
        assertEquals(emptyList<Map<String, String?>>(), folders.restoredPlaces)
    }

    @Test
    fun `A5 a failed create or rename emits FolderError and the dialog keeps its text`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)
        folders.failing = true

        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName("Trip"), FolderActionsIntent.ConfirmName)
        assertEquals(listOf(FolderActionsEvent.FolderError), events)
        assertEquals("Trip", vm.dialog().text)
        assertEquals(NameError.None, vm.dialog().error)
        assertFalse(vm.dialog().saving)
        assertEquals(3, folders.current().size)

        send(vm, FolderActionsIntent.CancelName, FolderActionsIntent.RenameFolder("G"))
        send(vm, FolderActionsIntent.EditName("Yard"), FolderActionsIntent.ConfirmName)
        assertEquals(listOf(FolderActionsEvent.FolderError, FolderActionsEvent.FolderError), events)
        assertEquals("Yard", vm.dialog().text)
        assertEquals("Garden", folders.current().single { it.id == "G" }.name)

        folders.failing = false
        send(vm, FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)
        assertEquals("Yard", folders.current().single { it.id == "G" }.name)
    }

    @Test
    fun `A5 a failed delete or NewForMove emits FolderError and nothing changes`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel(openFolderId = "G")
        val events = events(vm)
        folders.failing = true

        send(vm, FolderActionsIntent.AskDelete("G"), FolderActionsIntent.ConfirmDelete)
        assertEquals(listOf(FolderActionsEvent.FolderError), events)
        assertEquals(3, folders.current().size)
        assertEquals("G", scans.place("g1"))

        send(vm, FolderActionsIntent.Undo)
        assertEquals(emptyList<DeletedFolder>(), folders.restored)

        events.clear()
        send(vm, FolderActionsIntent.OpenMove(listOf("r1")), FolderActionsIntent.NewFolderForMove)
        send(vm, FolderActionsIntent.EditName("Trip"), FolderActionsIntent.ConfirmName)
        assertEquals(1, events.size)
        assertTrue(events.single() == FolderActionsEvent.FolderError || events.single() == FolderActionsEvent.MoveError)
        assertEquals("Trip", vm.dialog().text)
        assertNull(scans.place("r1"))
    }

    // Hardening

    @Test
    fun `H1 deleting a folder that holds a processing scan moves it to the root and keeps it building`() =
        runTest(dispatcher) {
            fixture()
            scans.set(scan("g1", "G", ScanStatus.PROCESSING), scan("g2", "G"), scan("r1"))
            val vm = viewModel()
            val events = events(vm)

            send(vm, FolderActionsIntent.AskDelete("G"))
            assertEquals(2, vm.uiState.value.deleteConfirm!!.scanCount)
            send(vm, FolderActionsIntent.ConfirmDelete)

            assertEquals(listOf(FolderActionsEvent.FolderDeleted("Garden")), events)
            assertNull(scans.place("g1"))
            assertEquals(ScanStatus.PROCESSING, scans.current().single { it.id == "g1" }.status)
            assertEquals(emptyList<String>(), scans.deleted)
            assertEquals(emptyList<String>(), scans.statusChanges)
        }

    @Test
    fun `H2 moving a scan while it processes keeps it building`() = runTest(dispatcher) {
        fixture()
        scans.set(scan("p1", status = ScanStatus.PROCESSING), scan("f1", status = ScanStatus.FAILED))
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.OpenMove(listOf("p1", "f1")), FolderActionsIntent.MoveTo("A"))

        assertEquals(listOf(FolderActionsEvent.Moved(2)), events)
        assertEquals("A", scans.place("p1"))
        assertEquals(ScanStatus.PROCESSING, scans.current().single { it.id == "p1" }.status)
        assertEquals(ScanStatus.FAILED, scans.current().single { it.id == "f1" }.status)
        assertEquals(emptyList<String>(), scans.statusChanges)
    }

    @Test
    fun `H3 a mixed selection from root search can move anywhere and Undo puts each back`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()
        val events = events(vm)

        send(vm, FolderActionsIntent.OpenMove(listOf("g1", "d1", "r1")))
        assertEquals(listOf(false, false, false, false), vm.uiState.value.move!!.targets.map { it.hereNow })

        send(vm, FolderActionsIntent.MoveTo("G"))
        assertEquals(listOf(FolderActionsEvent.Moved(3)), events)
        assertEquals(listOf("G", "G", "G"), listOf("g1", "d1", "r1").map { scans.place(it) })

        send(vm, FolderActionsIntent.Undo)
        assertEquals(listOf("G", "D", null), listOf("g1", "d1", "r1").map { scans.place(it) })
    }

    @Test
    fun `H4 emoji and RTL names are created, renamed and ordered unchanged`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()

        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName("🌵 Cacti"), FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)
        send(vm, FolderActionsIntent.RenameFolder("A"), FolderActionsIntent.EditName("גינה"))
        send(vm, FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)

        val names = folders.current().map { it.name }
        assertTrue("🌵 Cacti" in names)
        assertTrue("גינה" in names)

        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName("גינה"), FolderActionsIntent.ConfirmName)
        assertEquals(NameError.Taken, vm.dialog().error)
        send(vm, FolderActionsIntent.CancelName)

        send(vm, FolderActionsIntent.OpenMove(listOf("r1")))
        val targetNames = vm.uiState.value.move!!.targets.map { it.name }
        assertEquals(5, targetNames.size)
        assertTrue("🌵 Cacti" in targetNames && "גינה" in targetNames)
    }

    @Test
    fun `H5 a name of 61 characters is TooLong, 60 is fine, 60 emoji are 60 characters`() = runTest(dispatcher) {
        fixture()
        val vm = viewModel()

        send(vm, FolderActionsIntent.RenameFolder("A"), FolderActionsIntent.EditName("y".repeat(61)))
        send(vm, FolderActionsIntent.ConfirmName)
        assertEquals(NameError.TooLong, vm.dialog().error)
        assertEquals("alpha", folders.current().single { it.id == "A" }.name)

        send(vm, FolderActionsIntent.EditName("y".repeat(60)), FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)

        val emoji = "🌵".repeat(60)
        send(vm, FolderActionsIntent.NewFolder, FolderActionsIntent.EditName(emoji), FolderActionsIntent.ConfirmName)
        assertNull(vm.uiState.value.dialog)
        assertTrue(folders.current().any { it.name == emoji })
    }

    /** Scans in memory; [place] is a scan's folderId. */
    private class ListScanRepository : ScanRepository {
        private val list = MutableStateFlow<List<Scan>>(emptyList())
        val deleted = mutableListOf<String>()
        val statusChanges = mutableListOf<String>()

        fun set(vararg scans: Scan) {
            list.value = scans.toList()
        }

        fun current(): List<Scan> = list.value

        fun place(id: String): String? = list.value.single { it.id == id }.folderId

        fun movePlaces(places: Map<String, String?>) {
            list.value = list.value.map { if (it.id in places) it.copy(folderId = places.getValue(it.id)) else it }
        }

        override fun scans(): Flow<List<Scan>> = list

        override suspend fun get(id: String): Scan? = list.value.firstOrNull { it.id == id }

        override suspend fun create(name: String, createdAtMs: Long): Scan = error("not used")

        override suspend fun updateStatus(id: String, status: ScanStatus, splatCount: Int?, keyframeCount: Int?) {
            statusChanges += id
        }

        override suspend fun import(
            name: String,
            createdAtMs: Long,
            splatCount: Int,
            writeSplat: (OutputStream) -> Unit,
        ): Scan = error("not used")

        override suspend fun rename(id: String, name: String) = error("not used")

        override suspend fun delete(id: String) {
            deleted += id
        }

        override suspend fun sync() = Unit

        override suspend fun refresh(id: String) = Unit
    }

    /** The real repository's rules (trim, 1..60 code points, LibraryText.key duplicates) over [scans]. */
    private class FakeFolderRepository(private val scans: ListScanRepository) : FolderRepository {
        private val list = MutableStateFlow<List<LibraryFolder>>(emptyList())
        private var nextId = 1
        private var clock = 10_000L
        var failing = false
        val created = mutableListOf<String>()
        val deletedResults = mutableListOf<DeletedFolder>()
        val restored = mutableListOf<DeletedFolder>()
        val restoredPlaces = mutableListOf<Map<String, String?>>()

        fun set(vararg folders: LibraryFolder) {
            list.value = folders.toList()
        }

        fun current(): List<LibraryFolder> = list.value

        private fun failIfAsked() {
            if (failing) throw IOException("disk full")
        }

        private fun check(name: String, except: String?): String {
            val trimmed = name.trim()
            val length = trimmed.codePointCount(0, trimmed.length)
            require(length in 1..FolderRepository.MAX_NAME_LENGTH) { "bad name length $length" }
            if (list.value.any { it.id != except && LibraryText.key(it.name) == LibraryText.key(trimmed) }) {
                throw FolderNameTaken(trimmed)
            }
            return trimmed
        }

        override fun folders(): Flow<List<LibraryFolder>> = list

        override suspend fun create(name: String): LibraryFolder {
            created += name
            failIfAsked()
            val folder = LibraryFolder("N${nextId++}", check(name, except = null), clock++)
            list.value = listOf(folder) + list.value
            return folder
        }

        override suspend fun rename(id: String, name: String): LibraryFolder {
            failIfAsked()
            val old = list.value.firstOrNull { it.id == id } ?: throw NoSuchElementException(id)
            val renamed = old.copy(name = check(name, except = id))
            list.value = list.value.map { if (it.id == id) renamed else it }
            return renamed
        }

        override suspend fun delete(id: String): DeletedFolder {
            failIfAsked()
            val folder = list.value.firstOrNull { it.id == id } ?: throw NoSuchElementException(id)
            val held = scans.current().filter { it.folderId == id }.map { it.id }
            scans.movePlaces(held.associateWith { null })
            list.value = list.value - folder
            return DeletedFolder(folder, held).also { deletedResults += it }
        }

        override suspend fun restore(deleted: DeletedFolder) {
            restored += deleted
            list.value = list.value + deleted.folder
            scans.movePlaces(deleted.scanIds.associateWith { deleted.folder.id })
        }

        override suspend fun move(scanIds: Collection<String>, folderId: String?): Map<String, String?> {
            failIfAsked()
            if (folderId != null && list.value.none { it.id == folderId }) throw NoSuchElementException(folderId)
            val existing = scans.current().filter { it.id in scanIds }
            val previous = existing.associate { it.id to it.folderId }
            scans.movePlaces(existing.associate { it.id to folderId })
            return previous
        }

        override suspend fun restorePlaces(places: Map<String, String?>) {
            restoredPlaces += places
            val live = list.value.map { it.id }.toSet()
            scans.movePlaces(places.mapValues { (_, folder) -> folder?.takeIf { it in live } })
        }
    }
}
