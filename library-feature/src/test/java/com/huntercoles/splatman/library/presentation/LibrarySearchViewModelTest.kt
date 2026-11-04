package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.library.domain.LibrarySort
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.ScanStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #312 live search: scope per place, sort kept, selection, the default dispatcher, no match, emoji and RTL. */
class LibrarySearchViewModelTest : LibraryPlacesHarness() {

    @Test
    fun `A2 OpenSearch, Query and CloseSearch drive the search state`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.uiState.value.search.open)

        vm.onIntent(LibraryIntent.OpenSearch)
        runCurrent()
        assertEquals(SearchState(open = true, query = ""), vm.uiState.value.search)
        vm.onIntent(LibraryIntent.Query("desk"))
        runCurrent()
        assertEquals(SearchState(open = true, query = "desk"), vm.uiState.value.search)

        vm.onIntent(LibraryIntent.CloseSearch)
        runCurrent()
        assertFalse(vm.uiState.value.search.open)
        assertEquals("", vm.uiState.value.search.query, "closing clears the query")
        assertEquals(listOf("dl", "ch"), vm.cards().map { it.id }, "the root again, unfiltered")
        assertEquals(listOf("D", "G"), vm.folderIds())
    }

    @Test
    fun `A2 a root search covers every scan in every folder plus folder names`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        vm.search("desk")
        runCurrent()

        assertEquals(listOf("D"), vm.folderIds(), "matching folders first")
        assertEquals(listOf("dp", "dl", "dd"), vm.cards().map { it.id })
        assertEquals(listOf("Garden", null, "Desk stuff"), vm.cards().map { it.inFolderName })
    }

    @Test
    fun `A2 search results keep the sort`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        vm.search("DESK")
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.NAME))
        runCurrent()

        assertEquals(listOf("dd", "dl", "dp"), vm.cards().map { it.id }, "drawer, lamp, plant")
        assertEquals("DESK", vm.uiState.value.search.query)
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.OLDEST))
        runCurrent()
        assertEquals(listOf("dd", "dl", "dp"), vm.cards().map { it.id })
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.NEWEST))
        runCurrent()
        assertEquals(listOf("dp", "dl", "dd"), vm.cards().map { it.id })
    }

    @Test
    fun `A2 a folder search covers only that folder's scans`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel(folderId = "G")
        vm.search("desk")
        runCurrent()

        assertEquals(listOf("dp"), vm.cards().map { it.id })
        assertTrue(vm.uiState.value.folders.isEmpty())
        assertNull(vm.cards().single().inFolderName, "the folder line is for root search only")
    }

    @Test
    fun `A2 a blank query leaves the place unfiltered`() = runTest(dispatcher) {
        searchFixture()
        val root = viewModel()
        root.search("   ")
        runCurrent()
        assertEquals(listOf("D", "G"), root.folderIds())
        assertEquals(listOf("dl", "ch"), root.cards().map { it.id }, "only loose scans, as without search")
        assertTrue(root.cards().all { it.inFolderName == null })

        val garden = viewModel(folderId = "G")
        garden.search("")
        runCurrent()
        assertEquals(listOf("dp", "ro"), garden.cards().map { it.id })
    }

    @Test
    fun `A2 a processing scan in the results keeps updating without clearing the query`() = runTest(dispatcher) {
        searchFixture()
        val building = item("bu", "Desk build", createdAtMs = 9_000, folderId = "G", status = ScanStatus.PROCESSING)
        repository.emit(listOf(building, item("dl", "desk lamp", createdAtMs = 2_000)))
        val vm = viewModel()
        vm.search("build")
        runCurrent()
        assertEquals(CardState.Processing(null), vm.cards().single().state)

        progress.getValue("bu").emit(42)
        runCurrent()
        assertEquals(CardState.Processing(42), vm.cards().single().state)
        assertEquals("build", vm.uiState.value.search.query)

        repository.emit(listOf(building.copy(status = ScanStatus.READY, splatCount = 7)))
        runCurrent()
        assertEquals(CardState.Ready(7), vm.cards().single().state)
        assertEquals(SearchState(open = true, query = "build"), vm.uiState.value.search)
    }

    @Test
    fun `A2 each new query scrolls to the top`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        val events = collectEvents(vm)
        vm.onIntent(LibraryIntent.OpenSearch)
        runCurrent()
        events.clear()
        vm.onIntent(LibraryIntent.Query("d"))
        runCurrent()
        vm.onIntent(LibraryIntent.Query("de"))
        runCurrent()

        assertEquals(listOf<LibraryEvent>(LibraryEvent.ScrollToTop, LibraryEvent.ScrollToTop), events)
    }

    @Test
    fun `A3 CloseSearch ends the selection`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        vm.search("desk")
        runCurrent()
        vm.onIntent(LibraryIntent.LongPress("dp"))
        runCurrent()
        assertEquals(setOf("dp"), vm.uiState.value.selectedIds)

        vm.onIntent(LibraryIntent.CloseSearch)
        runCurrent()
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
        assertFalse(vm.uiState.value.selecting)
    }

    @Test
    fun `A3 the selection is per place`() = runTest(dispatcher) {
        searchFixture()
        val root = viewModel()
        val garden = viewModel(folderId = "G")
        root.onIntent(LibraryIntent.LongPress("dl"))
        runCurrent()
        assertEquals(setOf("dl"), root.uiState.value.selectedIds)
        assertTrue(garden.uiState.value.selectedIds.isEmpty())

        garden.onIntent(LibraryIntent.LongPress("dp"))
        runCurrent()
        assertEquals(setOf("dp"), garden.uiState.value.selectedIds)
        assertEquals(setOf("dl"), root.uiState.value.selectedIds)
        assertEquals(LibraryPlace.Folder("G", "Garden"), garden.uiState.value.place)
    }

    @Test
    fun `A3 a selected scan that stops matching leaves the selection`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel()
        vm.onIntent(LibraryIntent.OpenSearch)
        vm.onIntent(LibraryIntent.LongPress("dl"))
        vm.onIntent(LibraryIntent.Toggle("ch"))
        runCurrent()
        assertEquals(setOf("dl", "ch"), vm.uiState.value.selectedIds)

        vm.onIntent(LibraryIntent.Query("chair"))
        runCurrent()
        assertEquals(setOf("ch"), vm.uiState.value.selectedIds)
        assertEquals(listOf("ch"), vm.cards().filter { it.selected }.map { it.id })
    }

    @Test
    fun `A4 filtering and sorting run on the injected default dispatcher, not Main`() = runTest(dispatcher) {
        // A default dispatcher on its own clock: nothing it runs happens until that clock is advanced.
        val default = StandardTestDispatcher(TestCoroutineScheduler())
        searchFixture()
        val vm = viewModel(defaultDispatcher = default)
        runCurrent()
        assertEquals(LibraryContent.Loading, vm.uiState.value.content, "Main alone cannot build the grid")

        default.scheduler.runCurrent()
        runCurrent()
        assertEquals(listOf("dl", "ch"), vm.cards().map { it.id })

        vm.search("chair")
        runCurrent()
        assertEquals(listOf("dl", "ch"), vm.cards().map { it.id }, "the filter waits for the default dispatcher")
        default.scheduler.runCurrent()
        runCurrent()
        assertEquals(listOf("ch"), vm.cards().map { it.id })

        vm.onIntent(LibraryIntent.CloseSearch)
        default.scheduler.runCurrent()
        runCurrent()
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.OLDEST))
        runCurrent()
        assertEquals(listOf("dl", "ch"), vm.cards().map { it.id }, "the sort waits for the default dispatcher")
        default.scheduler.runCurrent()
        runCurrent()
        assertEquals(listOf("ch", "dl"), vm.cards().map { it.id })
    }

    @Test
    fun `H1 a search with no match is NoMatch with the query, at the root and in a folder`() = runTest(dispatcher) {
        searchFixture()
        val root = viewModel()
        root.search("zzz")
        runCurrent()
        assertEquals(LibraryContent.NoMatch("zzz"), root.uiState.value.content)
        assertTrue(root.uiState.value.folders.isEmpty())

        val garden = viewModel(folderId = "G")
        garden.search("Chair")
        runCurrent()
        assertEquals(LibraryContent.NoMatch("Chair"), garden.uiState.value.content, "Chair is at the root")
    }

    @Test
    fun `H2 emoji and RTL queries match as typed`() = runTest(dispatcher) {
        folders.emit(listOf(LibraryFolder("H", "ספרים ישנים", 1_000)))
        repository.emit(
            listOf(
                item("cactus", "🌵 Cactus", createdAtMs = 4_000),
                item("hebrew", "שולחן עבודה", createdAtMs = 3_000),
                item("arabic", "مكتب خشبي", createdAtMs = 2_000, folderId = "H"),
                item("plain", "Plain", createdAtMs = 1_000),
            ),
        )
        val vm = viewModel()
        vm.onIntent(LibraryIntent.OpenSearch)
        val expected = mapOf(
            "🌵" to listOf("cactus"),
            "עבודה" to listOf("hebrew"),
            "مكتب" to listOf("arabic"),
        )
        for ((query, ids) in expected) {
            vm.onIntent(LibraryIntent.Query(query))
            runCurrent()
            assertEquals(ids, vm.cards().map { it.id }, "query '$query'")
        }
        vm.onIntent(LibraryIntent.Query("ספרים"))
        runCurrent()
        assertEquals(listOf("H"), vm.folderIds(), "an RTL folder name matches")

        for (query in listOf("🌻", "كرسي")) {
            vm.onIntent(LibraryIntent.Query(query))
            runCurrent()
            assertEquals(LibraryContent.NoMatch(query), vm.uiState.value.content, "query '$query'")
        }
    }
}
