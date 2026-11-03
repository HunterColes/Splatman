package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.Sample
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val repository = FakeScanRepository()
    private val sample = Sample.fromFileName("cactus.spz")

    private fun scan(id: String, status: ScanStatus = ScanStatus.READY, splats: Int = 10) =
        Scan(id, "Scan $id", createdAtMs = 1_000, status = status, splatCount = splats, keyframeCount = 3, folder = id)

    private fun TestScope.viewModel(): LibraryViewModel {
        val vm = LibraryViewModel(repository, SampleRepository { listOf(sample) })
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun LibraryViewModel.cards(): List<LibraryCard> =
        (uiState.value.content as LibraryContent.Cards).cards

    @Test
    fun `loading until the repository's first list`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(LibraryContent.Loading, vm.uiState.value.content)
    }

    @Test
    fun `no scans is the empty state, with the sample to try and no FAB`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(emptyList())
        runCurrent()

        val state = vm.uiState.value
        assertEquals(LibraryContent.Empty, state.content)
        assertFalse(state.showFab)
        assertEquals(sample, state.firstSample)
    }

    @Test
    fun `scans become cards in the repository's order`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("b"), scan("a")))
        runCurrent()

        assertEquals(listOf("b", "a"), vm.cards().map { it.id })
        assertTrue(vm.uiState.value.showFab)
    }

    @Test
    fun `each status maps to its card state and where tapping goes`() {
        assertEquals(CardState.Ready(42), scan("r", ScanStatus.READY, 42).toCard().state)
        assertEquals(OpenTarget.Viewer("r"), scan("r").toCard().target)
        assertEquals(CardState.Processing, scan("p", ScanStatus.PROCESSING).toCard().state)
        assertEquals(OpenTarget.Processing("p"), scan("p", ScanStatus.PROCESSING).toCard().target)
        assertEquals(CardState.Failed, scan("f", ScanStatus.FAILED).toCard().state)
        assertEquals(OpenTarget.Processing("f"), scan("f", ScanStatus.FAILED).toCard().target)
        assertEquals(CardState.Captured, scan("c", ScanStatus.CAPTURED).toCard().state)
        assertEquals(OpenTarget.Processing("c"), scan("c", ScanStatus.CAPTURED).toCard().target)
    }

    @Test
    fun `long-press enters selection, taps toggle, leaving the last one exits`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a"), scan("b")))
        runCurrent()

        vm.onIntent(LibraryIntent.LongPress("a"))
        runCurrent()
        assertTrue(vm.uiState.value.selecting)
        assertEquals(listOf(true, false), vm.cards().map { it.selected })

        vm.onIntent(LibraryIntent.Toggle("b"))
        runCurrent()
        assertEquals(setOf("a", "b"), vm.uiState.value.selectedIds)

        vm.onIntent(LibraryIntent.Toggle("a"))
        vm.onIntent(LibraryIntent.Toggle("b"))
        runCurrent()
        assertFalse(vm.uiState.value.selecting)
    }

    @Test
    fun `back exits selection, and a scan that disappears drops out of it`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a"), scan("b")))
        runCurrent()
        vm.onIntent(LibraryIntent.LongPress("a"))
        vm.onIntent(LibraryIntent.Toggle("b"))
        runCurrent()

        repository.emit(listOf(scan("a")))
        runCurrent()
        assertEquals(setOf("a"), vm.uiState.value.selectedIds)

        vm.onIntent(LibraryIntent.ExitSelection)
        runCurrent()
        assertFalse(vm.uiState.value.selecting)
    }

    @Test
    fun `delete asks first, cancel keeps everything`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a")))
        runCurrent()

        vm.onIntent(LibraryIntent.RequestDelete())
        runCurrent()
        assertNull(vm.uiState.value.confirmDelete, "nothing selected: nothing to ask about")

        vm.onIntent(LibraryIntent.LongPress("a"))
        vm.onIntent(LibraryIntent.RequestDelete())
        runCurrent()
        assertEquals(setOf("a"), vm.uiState.value.confirmDelete)

        vm.onIntent(LibraryIntent.CancelDelete)
        runCurrent()
        assertNull(vm.uiState.value.confirmDelete)
        assertEquals(listOf("a"), vm.cards().map { it.id })
        assertTrue(repository.deleted.isEmpty())
    }

    @Test
    fun `confirmed delete hides the scans at once and deletes them when the undo window closes`() =
        runTest(dispatcher) {
            val vm = viewModel()
            repository.emit(listOf(scan("a"), scan("b")))
            runCurrent()
            vm.onIntent(LibraryIntent.LongPress("a"))
            vm.onIntent(LibraryIntent.RequestDelete())
            vm.onIntent(LibraryIntent.ConfirmDelete)
            runCurrent()

            val state = vm.uiState.value
            assertEquals(listOf("b"), vm.cards().map { it.id })
            assertEquals(PendingDelete(setOf("a")), state.undo)
            assertFalse(state.selecting)
            assertNull(state.confirmDelete)
            assertTrue(repository.deleted.isEmpty())

            advanceTimeBy(UNDO_WINDOW_MS - 1)
            runCurrent()
            assertTrue(repository.deleted.isEmpty())

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf("a"), repository.deleted)
            assertNull(vm.uiState.value.undo)
        }

    @Test
    fun `undo brings the scans back and deletes nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a"), scan("b")))
        runCurrent()
        vm.onIntent(LibraryIntent.RequestDelete(setOf("a")))
        vm.onIntent(LibraryIntent.ConfirmDelete)
        runCurrent()

        vm.onIntent(LibraryIntent.Undo)
        advanceTimeBy(UNDO_WINDOW_MS * 2)
        runCurrent()

        assertEquals(listOf("a", "b"), vm.cards().map { it.id })
        assertNull(vm.uiState.value.undo)
        assertTrue(repository.deleted.isEmpty())
    }

    @Test
    fun `deleting the last scan shows the empty state during the undo window`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a")))
        runCurrent()
        vm.onIntent(LibraryIntent.RequestDelete(setOf("a")))
        vm.onIntent(LibraryIntent.ConfirmDelete)
        runCurrent()

        assertEquals(LibraryContent.Empty, vm.uiState.value.content)
    }

    @Test
    fun `a dismissed snackbar deletes at once, and a second delete commits the first`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(listOf(scan("a"), scan("b"), scan("c")))
        runCurrent()

        vm.onIntent(LibraryIntent.RequestDelete(setOf("a")))
        vm.onIntent(LibraryIntent.ConfirmDelete)
        runCurrent()
        vm.onIntent(LibraryIntent.RequestDelete(setOf("b")))
        vm.onIntent(LibraryIntent.ConfirmDelete)
        runCurrent()
        assertEquals(listOf("a"), repository.deleted)
        assertEquals(PendingDelete(setOf("b")), vm.uiState.value.undo)

        vm.onIntent(LibraryIntent.CommitDelete)
        runCurrent()
        assertEquals(listOf("a", "b"), repository.deleted)
        assertNull(vm.uiState.value.undo)
    }

    @Test
    fun `rename trims and ignores a blank name`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(LibraryIntent.Rename("a", "  Desk plant "))
        vm.onIntent(LibraryIntent.Rename("a", "   "))
        runCurrent()
        assertEquals(listOf("a" to "Desk plant"), repository.renamed)
    }

    private class FakeScanRepository : ScanRepository {
        private val lists = MutableStateFlow<List<Scan>?>(null)
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()

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

        override suspend fun rename(id: String, name: String) {
            renamed += id to name
        }

        override suspend fun delete(id: String) {
            deleted += id
            lists.value = lists.value?.filterNot { it.id == id }
        }

        override suspend fun sync() = Unit

        override suspend fun refresh(id: String) = Unit
    }
}
