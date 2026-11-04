package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.library.data.prefs.LibraryPreferences
import com.huntercoles.splatman.library.domain.LibrarySort
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** #312 places and sort: the root (folders, then loose scans), a folder, the remembered sort, a folder vanishing. */
class LibraryPlacesTest : LibraryPlacesHarness() {

    @Test
    fun `A1 the root shows folders first, then loose scans, both in every sort`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel()
        val expected = mapOf(
            LibrarySort.NEWEST to (listOf("F1", "F2") to listOf("s3", "s2", "s1")),
            LibrarySort.OLDEST to (listOf("F2", "F1") to listOf("s1", "s2", "s3")),
            LibrarySort.NAME to (listOf("F2", "F1") to listOf("s2", "s1", "s3")),
            LibrarySort.SIZE to (listOf("F1", "F2") to listOf("s1", "s2", "s3")),
        )
        for ((sort, order) in expected) {
            vm.onIntent(LibraryIntent.SetSort(sort))
            runCurrent()
            assertEquals(sort, vm.uiState.value.sort)
            assertEquals(order.first, vm.folderIds(), "folders by $sort")
            assertEquals(order.second, vm.cards().map { it.id }, "loose scans by $sort (no folder's scans)")
        }
        assertEquals(LibraryPlace.Root, vm.uiState.value.place)
        assertTrue(vm.cards().all { it.inFolderName == null }, "no folder line outside a root search")
    }

    @Test
    fun `A1 a folder cell counts its scans and sums their sizes`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel()
        runCurrent()

        val cells = vm.uiState.value.folders.associateBy { it.id }
        assertEquals("alpha", cells.getValue("F2").name)
        assertEquals(2, cells.getValue("F2").scanCount)
        assertEquals(50L, cells.getValue("F2").sizeBytes)
        assertEquals(1, cells.getValue("F1").scanCount)
        assertEquals(60L, cells.getValue("F1").sizeBytes)
    }

    @Test
    fun `A1 a folder cell shows up to 4 thumbnails in the current sort`() = runTest(dispatcher) {
        val thumbs = (1..5).map { ScanThumbnail("/scans/t$it/thumbnail.png", stamp = it.toLong()) }
        folders.emit(listOf(LibraryFolder("F", "Five", 1_000)))
        repository.emit(
            thumbs.mapIndexed { i, t -> item("t${i + 1}", createdAtMs = (i + 1) * 1_000L, folderId = "F", thumbnail = t) },
        )
        val vm = viewModel()
        runCurrent()

        assertEquals(thumbs.reversed().take(4), vm.uiState.value.folders.single().thumbnails, "newest first")
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.OLDEST))
        runCurrent()
        assertEquals(thumbs.take(4), vm.uiState.value.folders.single().thumbnails, "oldest first")
    }

    @Test
    fun `A1 inside a folder only its scans show, in the sort, and no folders`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel(folderId = "F2")
        runCurrent()

        assertEquals(LibraryPlace.Folder("F2", "alpha"), vm.uiState.value.place)
        assertTrue(vm.uiState.value.folders.isEmpty())
        assertEquals(listOf("f2b", "f2a"), vm.cards().map { it.id })
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.SIZE))
        runCurrent()
        assertEquals(listOf("f2b", "f2a"), vm.cards().map { it.id }, "30 B before 20 B")
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.OLDEST))
        runCurrent()
        assertEquals(listOf("f2a", "f2b"), vm.cards().map { it.id })
    }

    @Test
    fun `A1 Empty only with no scans and no folders`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.emit(emptyList())
        runCurrent()
        assertEquals(LibraryContent.Empty, vm.uiState.value.content)

        folders.emit(listOf(LibraryFolder("F", "Desk", 1_000)))
        runCurrent()
        assertNotEquals(LibraryContent.Empty, vm.uiState.value.content, "a root with only folders is not empty")
        assertEquals(listOf("F"), vm.folderIds())

        repository.emit(listOf(item("in", folderId = "F")))
        runCurrent()
        assertNotEquals(LibraryContent.Empty, vm.uiState.value.content, "every scan in a folder")
        assertEquals(listOf("F"), vm.folderIds())
        assertTrue(vm.uiState.value.showFab)
    }

    @Test
    fun `A1 a folder with no scans is FolderEmpty, not Empty`() = runTest(dispatcher) {
        folders.emit(listOf(LibraryFolder("F", "Desk", 1_000)))
        repository.emit(listOf(item("loose")))
        val vm = viewModel(folderId = "F")
        runCurrent()

        assertEquals(LibraryContent.FolderEmpty, vm.uiState.value.content)
        assertTrue(vm.uiState.value.showFab, "the FAB stays in an empty folder")
    }

    @Test
    fun `A1 new scans land in a folder while it is open, at the root once it closes`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel(folderId = "F2")
        runCurrent()
        assertEquals("F2", landing.folderId.value)

        clear(vm)
        assertNull(landing.folderId.value)
    }

    @Test
    fun `A5 SetSort persists through LibraryPreferences`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel()
        runCurrent()
        assertEquals(LibrarySort.NEWEST, vm.uiState.value.sort)

        vm.onIntent(LibraryIntent.SetSort(LibrarySort.NAME))
        runCurrent()
        assertEquals(LibrarySort.NAME, preferences.sort.value)
        assertEquals("NAME", store.values["sort"])
        assertEquals(LibrarySort.NAME, LibraryPreferences(store).sort.value, "kept across restarts")
    }

    @Test
    fun `A5 every place reads the same remembered sort`() = runTest(dispatcher) {
        sortFixture()
        preferences.setSort(LibrarySort.SIZE)
        val root = viewModel()
        val folder = viewModel(folderId = "F2")
        runCurrent()
        assertEquals(LibrarySort.SIZE, root.uiState.value.sort)
        assertEquals(LibrarySort.SIZE, folder.uiState.value.sort)
        assertEquals(listOf("f2b", "f2a"), folder.cards().map { it.id })

        root.onIntent(LibraryIntent.SetSort(LibrarySort.OLDEST))
        runCurrent()
        assertEquals(LibrarySort.OLDEST, folder.uiState.value.sort, "a sort picked at the root holds in the folder")
        assertEquals(listOf("f2a", "f2b"), folder.cards().map { it.id })
    }

    @Test
    fun `A5 a new sort scrolls to the top`() = runTest(dispatcher) {
        sortFixture()
        val vm = viewModel()
        val events = collectEvents(vm)
        vm.onIntent(LibraryIntent.SetSort(LibrarySort.NAME))
        runCurrent()

        assertEquals(listOf<LibraryEvent>(LibraryEvent.ScrollToTop), events)
    }

    @Test
    fun `H3 a folder deleted while open emits LeaveFolder`() = runTest(dispatcher) {
        searchFixture()
        val vm = viewModel(folderId = "G")
        val events = collectEvents(vm)
        runCurrent()
        assertTrue(LibraryEvent.LeaveFolder !in events)

        folders.delete("G")
        repository.emit(listOf(item("dp", "Desk plant"), item("ro", "Rose")))
        runCurrent()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.LeaveFolder), events)
    }

    /**
     * 500 scans: 20 folders of 20 scans each and 100 loose ones, emitted shuffled. Loose scan i (0..99) is made at
     * 1 000 000 + i s, named "Scan ${(i * 61) % 100 + 1}", sized ((i * 37) % 100 + 1) kB; loose-50 is CAPTURED.
     * Folder j (0..19) is made at 2 000 000 + ((j * 7) % 20) s, named "Folder ${(j * 9 + 5) % 20 + 1}", and each of
     * its scans is ((j * 13) % 20 + 1) kB.
     */
    private fun manyFixture() {
        val loose = (0 until 100).map { i ->
            item(
                id = "loose-$i",
                name = "Scan ${(i * 61) % 100 + 1}",
                createdAtMs = 1_000_000L + i * 1_000L,
                sizeBytes = ((i * 37) % 100 + 1) * 1_000L,
                status = if (i == 50) ScanStatus.CAPTURED else ScanStatus.READY,
            )
        }
        val inFolders = (0 until 20).flatMap { j ->
            (0 until 20).map { k ->
                item(
                    id = "f$j-$k",
                    name = "Item $j $k",
                    createdAtMs = 3_000_000L + (j * 20 + k) * 1_000L,
                    sizeBytes = ((j * 13) % 20 + 1) * 1_000L,
                    folderId = "folder-$j",
                )
            }
        }
        folders.emit(
            (0 until 20).map { j ->
                LibraryFolder("folder-$j", "Folder ${(j * 9 + 5) % 20 + 1}", 2_000_000L + ((j * 7) % 20) * 1_000L)
            }.shuffled(Random(312)),
        )
        repository.emit((loose + inFolders).shuffled(Random(500)))
    }

    @Test
    fun `H4 500 scans across 20 folders give the right first and last item for every sort`() = runTest(dispatcher) {
        manyFixture()
        val vm = viewModel()
        runCurrent()
        assertEquals(20, vm.uiState.value.folders.size)
        assertTrue(vm.uiState.value.folders.all { it.scanCount == 20 })
        assertEquals(100, vm.cards().size)

        // sort to (first folder, last folder) and (first loose scan, last loose scan)
        val expected = mapOf(
            LibrarySort.NEWEST to (("folder-17" to "folder-0") to ("loose-99" to "loose-0")),
            LibrarySort.OLDEST to (("folder-0" to "folder-17") to ("loose-0" to "loose-99")),
            LibrarySort.NAME to (("folder-15" to "folder-6") to ("loose-0" to "loose-59")),
            LibrarySort.SIZE to (("folder-3" to "folder-0") to ("loose-27" to "loose-50")),
        )
        for ((sort, ends) in expected) {
            vm.onIntent(LibraryIntent.SetSort(sort))
            runCurrent()
            val folderIds = vm.folderIds()
            val cardIds = vm.cards().map { it.id }
            assertEquals(ends.first, folderIds.first() to folderIds.last(), "folders by $sort")
            assertEquals(ends.second, cardIds.first() to cardIds.last(), "loose scans by $sort")
        }
    }
}
