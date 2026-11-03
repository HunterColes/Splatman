package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.data.repository.FakeScanDao
import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    @TempDir
    lateinit var tmp: File

    private val files = mutableMapOf<String, ByteArray>()
    private val requests = ImportRequests()
    private val repository by lazy {
        ScanRepositoryImpl(FakeScanDao(), File(tmp, "scans").apply { mkdirs() }, ProcessingCanceller { }, Dispatchers.Unconfined)
    }

    /** When set, the import waits on it: a slow read. */
    private var gate: CompletableDeferred<Unit>? = null

    private fun viewModel(): ImportViewModel {
        val importer = SplatImporter(
            validator = ImportValidator(maxSplats = 1_000),
            scans = repository,
            freeBytes = { Long.MAX_VALUE },
            io = dispatcher,
        )
        val sources = ImportSourceFactory { uri ->
            object : ImportSource {
                override val fileName = uri.substringAfterLast('/')
                override fun open(): InputStream = ByteArrayInputStream(files.getValue(uri))
            }
        }
        return ImportViewModel(SlowImporter(importer), sources, requests, dispatcher)
    }

    /** Stands in for a slow read: waits on [gate] before importing. */
    private inner class SlowImporter(private val real: SplatImporter) : Importer {
        override suspend fun import(source: ImportSource): ImportOutcome {
            gate?.await()
            return real.import(source)
        }
    }

    @Test
    fun `a quick valid import opens the viewer without ever showing the dialog`() = runTest(dispatcher) {
        files["content://x/garden.splat"] = ImportFixtures.splat(ImportFixtures.cloud(3))
        val vm = viewModel()
        val seen = mutableListOf<ImportDialog?>()

        vm.onIntent(ImportIntent.Pick("content://x/garden.splat"))
        seen += vm.uiState.value.dialog
        runCurrent()
        seen += vm.uiState.value.dialog
        advanceTimeBy(VALIDATING_DIALOG_DELAY_MS + 1)
        seen += vm.uiState.value.dialog

        assertEquals(listOf<ImportDialog?>(null, null, null), seen)
        val opened = vm.uiState.value.opened!!
        assertEquals("garden", opened.name)
        assertEquals(opened.scanId, repository.get(opened.scanId)?.id)
        vm.onIntent(ImportIntent.Opened)
        assertNull(vm.uiState.value.opened)
    }

    @Test
    fun `a slow import shows the validating dialog after 300 ms`() = runTest(dispatcher) {
        files["content://x/desk.ply"] = ImportFixtures.ply(ImportFixtures.cloud(3))
        gate = CompletableDeferred()
        val vm = viewModel()

        vm.onIntent(ImportIntent.Pick("content://x/desk.ply"))
        advanceTimeBy(VALIDATING_DIALOG_DELAY_MS - 1)
        assertNull(vm.uiState.value.dialog)
        advanceTimeBy(2)
        assertEquals(ImportDialog.Validating("desk.ply"), vm.uiState.value.dialog)

        gate!!.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.dialog)
        assertEquals("desk", vm.uiState.value.opened?.name)
    }

    @Test
    fun `back on the validating dialog cancels the import`() = runTest(dispatcher) {
        files["content://x/desk.ply"] = ImportFixtures.ply(ImportFixtures.cloud(3))
        gate = CompletableDeferred()
        val vm = viewModel()

        vm.onIntent(ImportIntent.Pick("content://x/desk.ply"))
        advanceTimeBy(VALIDATING_DIALOG_DELAY_MS + 1)
        vm.onIntent(ImportIntent.Cancel)
        gate!!.complete(Unit)
        runCurrent()

        assertNull(vm.uiState.value.dialog)
        assertNull(vm.uiState.value.opened)
        assertEquals(emptyList<Any>(), File(tmp, "scans").listFiles()!!.toList())
    }

    @Test
    fun `an invalid file turns into the error dialog, and OK closes it`() = runTest(dispatcher) {
        files["content://x/points.ply"] = ImportFixtures.pointCloudPly(4)
        val vm = viewModel()

        vm.onIntent(ImportIntent.Pick("content://x/points.ply"))
        runCurrent()

        assertEquals(ImportDialog.Error(ImportMessage(R.string.imp_point_cloud)), vm.uiState.value.dialog)
        assertNull(vm.uiState.value.opened)
        vm.onIntent(ImportIntent.Dismiss)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `a file from Open with is imported once`() = runTest(dispatcher) {
        files["content://y/shared.splat"] = ImportFixtures.splat(ImportFixtures.cloud(2))
        val vm = viewModel()
        runCurrent()

        requests.offer("content://y/shared.splat")
        runCurrent()

        assertEquals("shared", vm.uiState.value.opened?.name)
        assertNull(requests.requests.value)
        assertEquals(1, File(tmp, "scans").listFiles()!!.size)
    }
}
