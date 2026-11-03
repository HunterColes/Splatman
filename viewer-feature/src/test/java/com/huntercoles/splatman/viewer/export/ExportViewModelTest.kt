package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import com.huntercoles.splatman.core.preferences.ExportFormat as SettingsFormat

class ExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val cloud = TestClouds.cloud(10)

    private class MapStore : PreferenceStore {
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

    private class FakeTarget(var failWrites: Boolean = false, var free: Long? = null) : ExportTarget {
        val documents = mutableMapOf<String, ByteArrayOutputStream>()
        val shared = mutableMapOf<String, ByteArrayOutputStream>()
        val deleted = mutableListOf<String>()
        var clears = 0
        var onWrite: () -> Unit = {}

        private fun sink(into: ByteArrayOutputStream): OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) throw IOException("disk full")
                onWrite()
                into.write(b, off, len)
            }
        }

        override fun openDocument(uri: String): OutputStream = sink(ByteArrayOutputStream().also { documents[uri] = it })

        override fun createInFolder(treeUri: String, fileName: String): String = "$treeUri/$fileName"

        override fun deleteDocument(uri: String) {
            deleted += uri
            documents.remove(uri)
        }

        override fun clearShareFiles() {
            clears++
            shared.clear()
        }

        override fun openShareFile(fileName: String): OutputStream =
            sink(ByteArrayOutputStream().also { shared[fileName] = it })

        override fun shareUri(fileName: String): String = "content://app.exports/exports/$fileName"

        override fun freeBytes(): Long? = free
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun viewModel(target: ExportTarget, settings: SettingsPreferences = SettingsPreferences(MapStore())) =
        ExportViewModel(ExportUseCase(dispatcher), target, settings)

    private fun item(name: String, n: Int) = TestClouds.cloud(n).let { c ->
        ExportItem(ExportEntry(name, c.count, c.shDegree)) { c }
    }

    @Test
    fun `with no scene there is nothing to export`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        assertFalse(vm.uiState.value.canExport)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        vm.onIntent(ExportIntent.Share)
        advanceUntilIdle()
        assertTrue(target.documents.isEmpty() && target.shared.isEmpty())
        assertEquals(ExportStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun `the sheet opens on PLY with the scan's file name and each format's size`() = test {
        val vm = viewModel(FakeTarget())
        vm.setScene("Measuring tape", cloud)
        vm.onIntent(ExportIntent.Show)
        val state = vm.uiState.value
        assertTrue(state.sheetOpen)
        assertFalse(state.multi)
        assertEquals(ExportFormat.PLY, state.selected)
        assertEquals("Measuring tape.ply", state.fileName)
        assertEquals(ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.size().toLong(), state.size(ExportFormat.PLY))
        assertEquals(10L * 32, state.size(ExportFormat.SPLAT))
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        assertEquals("Measuring tape.splat", vm.uiState.value.fileName)
        vm.onIntent(ExportIntent.Select(ExportFormat.SPZ))
        assertEquals(ExportFormat.SPLAT, vm.uiState.value.selected)
    }

    @Test
    fun `the sheet starts on the Settings default format each time it opens`() = test {
        val settings = SettingsPreferences(MapStore()).apply { setExportFormat(SettingsFormat.Splat) }
        val vm = viewModel(FakeTarget(), settings)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Show)
        assertEquals(ExportFormat.SPLAT, vm.uiState.value.selected)
        vm.onIntent(ExportIntent.Select(ExportFormat.PLY))
        vm.onIntent(ExportIntent.Dismiss)
        settings.setExportFormat(SettingsFormat.Splat)
        vm.onIntent(ExportIntent.Show)
        assertEquals(ExportFormat.SPLAT, vm.uiState.value.selected)
    }

    @Test
    fun `save writes the format's bytes to the created document, then closes with a saved notice`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        assertInstanceOf(ExportStatus.Working::class.java, vm.uiState.value.status)
        assertFalse(vm.uiState.value.canExport)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(ExportStatus.Idle, state.status)
        assertFalse(state.sheetOpen)
        assertEquals(listOf("Cactus.ply"), state.saved)
        val expected = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()
        assertArrayEquals(expected, target.documents.getValue("content://doc/1").toByteArray())
        vm.onIntent(ExportIntent.SavedShown)
        assertNull(vm.uiState.value.saved)
    }

    @Test
    fun `a cancelled picker exports nothing`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.SaveTo(null))
        vm.onIntent(ExportIntent.SaveToFolder(null))
        advanceUntilIdle()
        assertTrue(target.documents.isEmpty())
        assertEquals(ExportStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun `a failed write shows the error state, keeps the sheet open and removes the half-written file`() = test {
        val target = FakeTarget(failWrites = true)
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(ExportStatus.Failed(ExportUseCase.WRITE_ERROR), state.status)
        assertTrue(state.sheetOpen)
        assertTrue(state.canExport)
        assertNull(state.saved)
        assertEquals(listOf("content://doc/1"), target.deleted)
    }

    @Test
    fun `Back during a write cancels it, removes the file and closes the sheet`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        target.onWrite = { if (vm.uiState.value.working) vm.onIntent(ExportIntent.Dismiss) }
        vm.setScene("Big", TestClouds.cloud(5000, 3))
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.SaveTo("content://doc/big"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(ExportStatus.Idle, state.status)
        assertFalse(state.sheetOpen)
        assertNull(state.saved)
        assertEquals(listOf("content://doc/big"), target.deleted)
    }

    @Test
    fun `low storage disables both buttons and writes nothing`() = test {
        val plySize = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.size().toLong()
        val target = FakeTarget(free = plySize + ExportStorage.HEADROOM - 1)
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Show)
        assertTrue(vm.uiState.value.lowStorage)
        assertFalse(vm.uiState.value.canExport)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        vm.onIntent(ExportIntent.Share)
        advanceUntilIdle()
        assertTrue(target.documents.isEmpty() && target.shared.isEmpty())
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        assertFalse(vm.uiState.value.lowStorage, "the smaller format fits")
        assertTrue(vm.uiState.value.canExport)
    }

    @Test
    fun `share writes the cache file, hands its provider uri to the screen once, then closes`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        vm.onIntent(ExportIntent.Share)
        advanceUntilIdle()
        assertEquals(1, target.clears)
        assertArrayEquals(SplatCodec.write(cloud), target.shared.getValue("Cactus.splat").toByteArray())
        assertEquals(
            ShareRequest(listOf("content://app.exports/exports/Cactus.splat"), listOf("Cactus.splat")),
            vm.uiState.value.share,
        )
        assertTrue(vm.uiState.value.sheetOpen)
        vm.onIntent(ExportIntent.ShareLaunched)
        assertNull(vm.uiState.value.share)
        assertFalse(vm.uiState.value.sheetOpen)
    }

    @Test
    fun `several scans share as one batch with unique names and summed sizes`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setItems(listOf(item("Desk", 3), item("Desk", 4), item("Plant", 5)))
        vm.onIntent(ExportIntent.Show)
        val state = vm.uiState.value
        assertTrue(state.multi)
        assertEquals(listOf("Desk.ply", "Desk (2).ply", "Plant.ply"), state.fileNames)
        assertEquals(12L * 32, state.size(ExportFormat.SPLAT))
        vm.onIntent(ExportIntent.Share)
        advanceUntilIdle()
        assertEquals(state.fileNames, vm.uiState.value.share?.fileNames)
        assertEquals(3, vm.uiState.value.share?.uris?.size)
        assertEquals(3, target.shared.size)
    }

    @Test
    fun `several scans save into the chosen folder, one document each`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setItems(listOf(item("Desk", 3), item("Plant", 5)))
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.SaveTo("content://doc/ignored"))
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        vm.onIntent(ExportIntent.SaveToFolder("content://tree/x"))
        advanceUntilIdle()
        assertEquals(setOf("content://tree/x/Desk.splat", "content://tree/x/Plant.splat"), target.documents.keys)
        assertEquals(5 * 32, target.documents.getValue("content://tree/x/Plant.splat").size())
        assertEquals(listOf("Desk.splat", "Plant.splat"), vm.uiState.value.saved)
        assertFalse(vm.uiState.value.sheetOpen)
    }

    @Test
    fun `a scan that fails to load stops the batch and removes what was written`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        val broken = ExportItem(ExportEntry("Broken", 3, 0)) { throw IOException("gone") }
        vm.setItems(listOf(item("Desk", 3), broken))
        vm.onIntent(ExportIntent.Show)
        vm.onIntent(ExportIntent.SaveToFolder("content://tree/x"))
        advanceUntilIdle()
        assertEquals(ExportStatus.Failed(ExportUseCase.WRITE_ERROR), vm.uiState.value.status)
        assertEquals(listOf("content://tree/x/Desk.ply"), target.deleted)
        assertTrue(target.documents.isEmpty())
    }

    @Test
    fun `leaving the scene disables export`() = test {
        val vm = viewModel(FakeTarget())
        vm.setScene("Cactus", cloud)
        assertTrue(vm.uiState.value.canExport)
        vm.setScene(null, null)
        assertFalse(vm.uiState.value.canExport)
    }
}
