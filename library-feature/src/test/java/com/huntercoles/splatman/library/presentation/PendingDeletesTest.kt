package com.huntercoles.splatman.library.presentation

import com.huntercoles.splatman.library.data.repository.FakeScanDao
import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [PendingDeletes] over the real repository and folder tree: what a Viewer delete leaves on disk. */
class PendingDeletesTest {

    @TempDir
    lateinit var tmp: File

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeScanDao()
    private val root by lazy { File(tmp, "scans").apply { mkdirs() } }
    private val repository by lazy {
        ScanRepositoryImpl(dao, root, ProcessingCanceller { }, Dispatchers.Unconfined)
    }

    private suspend fun scanWithFiles(name: String): String {
        val scan = repository.create(name, createdAtMs = 1_000)
        File(root, scan.folder).resolve("splat.ply").writeText("not really")
        return scan.id
    }

    @Test
    fun `a Viewer delete undone inside the window keeps the row and the folder`() = runTest(dispatcher) {
        val id = scanWithFiles("desk")
        val deletes = PendingDeletes(repository, backgroundScope)

        deletes.defer(setOf(id))
        runCurrent()
        assertEquals(setOf(id), deletes.state.value.hidden)
        advanceTimeBy(UNDO_WINDOW_MS - 1)
        deletes.undo()
        advanceTimeBy(UNDO_WINDOW_MS * 2)
        runCurrent()

        assertNotNull(repository.get(id))
        assertTrue(File(root, id).resolve("splat.ply").isFile)
        assertEquals(PendingDeletes.State(), deletes.state.value)
    }

    @Test
    fun `without Undo the folder and the row are gone once the window closes`() = runTest(dispatcher) {
        val id = scanWithFiles("desk")
        val deletes = PendingDeletes(repository, backgroundScope)

        deletes.defer(setOf(id))
        advanceTimeBy(UNDO_WINDOW_MS - 1)
        runCurrent()
        assertTrue(File(root, id).isDirectory, "nothing is touched inside the window")

        advanceTimeBy(2)
        runCurrent()
        assertFalse(File(root, id).exists())
        assertNull(repository.get(id))
        assertEquals(PendingDeletes.State(), deletes.state.value)
    }

    @Test
    fun `an app killed inside the window keeps the scan whole on the next start`() = runTest(dispatcher) {
        val id = scanWithFiles("desk")
        val process = CoroutineScope(coroutineContext + Job())
        PendingDeletes(repository, process).defer(setOf(id))
        runCurrent()
        process.cancel()

        // Next start: a fresh store hides nothing, and the Library's sync keeps the row.
        val next = PendingDeletes(repository, backgroundScope)
        advanceTimeBy(UNDO_WINDOW_MS * 2)
        repository.sync()
        assertEquals(PendingDeletes.State(), next.state.value)
        assertNotNull(repository.get(id))
        assertTrue(File(root, id).resolve("splat.ply").isFile)
    }

    @Test
    fun `two quick deletes commit the first and keep the second undoable`() = runTest(dispatcher) {
        val first = scanWithFiles("one")
        val second = scanWithFiles("two")
        val deletes = PendingDeletes(repository, backgroundScope)

        deletes.defer(setOf(first))
        runCurrent()
        deletes.defer(setOf(second))
        runCurrent()
        assertNull(repository.get(first))
        assertFalse(File(root, first).exists())
        assertEquals(PendingDelete(setOf(second)), deletes.state.value.offer)

        deletes.undo()
        advanceTimeBy(UNDO_WINDOW_MS * 2)
        runCurrent()
        assertNotNull(repository.get(second))
        assertTrue(File(root, second).isDirectory)
    }
}
