package com.huntercoles.splatman.viewer.export

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

class ExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val cloud = TestClouds.cloud(10)

    private class FakeTarget(var failWrites: Boolean = false) : ExportTarget {
        val documents = mutableMapOf<String, ByteArrayOutputStream>()
        val shared = mutableMapOf<String, ByteArrayOutputStream>()

        private fun sink(into: ByteArrayOutputStream): OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (failWrites) throw IOException("disk full")
                into.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) throw IOException("disk full")
                into.write(b, off, len)
            }
        }

        override fun openDocument(uri: String): OutputStream = sink(ByteArrayOutputStream().also { documents[uri] = it })

        override fun openShareFile(fileName: String): OutputStream =
            sink(ByteArrayOutputStream().also { shared[fileName] = it })

        override fun shareUri(fileName: String): String = "content://app.exports/exports/$fileName"
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun viewModel(target: ExportTarget) = ExportViewModel(ExportUseCase(dispatcher), target)

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
    fun `the sheet opens on PLY with the scan's file name`() = test {
        val vm = viewModel(FakeTarget())
        vm.setScene("Measuring tape", cloud)
        vm.onIntent(ExportIntent.Show)
        val state = vm.uiState.value
        assertTrue(state.sheetOpen)
        assertEquals(ExportFormat.PLY, state.selected)
        assertEquals("Measuring tape.ply", state.fileName)
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        assertEquals("Measuring tape.splat", vm.uiState.value.fileName)
        vm.onIntent(ExportIntent.Select(ExportFormat.SPZ))
        assertEquals(ExportFormat.SPLAT, vm.uiState.value.selected)
    }

    @Test
    fun `save writes the format's bytes to the created document, working then saved`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        assertInstanceOf(ExportStatus.Working::class.java, vm.uiState.value.status)
        assertFalse(vm.uiState.value.canExport)
        advanceUntilIdle()
        assertEquals(ExportStatus.Saved("Cactus.ply"), vm.uiState.value.status)
        val expected = ByteArrayOutputStream().also { PlyWriter.write(cloud, it) }.toByteArray()
        assertArrayEquals(expected, target.documents.getValue("content://doc/1").toByteArray())
    }

    @Test
    fun `a cancelled picker exports nothing`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.SaveTo(null))
        advanceUntilIdle()
        assertTrue(target.documents.isEmpty())
        assertEquals(ExportStatus.Idle, vm.uiState.value.status)
    }

    @Test
    fun `a failed write shows the error state`() = test {
        val vm = viewModel(FakeTarget(failWrites = true))
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.SaveTo("content://doc/1"))
        advanceUntilIdle()
        assertEquals(ExportStatus.Failed(ExportUseCase.WRITE_ERROR), vm.uiState.value.status)
        assertTrue(vm.uiState.value.canExport)
    }

    @Test
    fun `share writes the cache file and hands its provider uri to the screen once`() = test {
        val target = FakeTarget()
        val vm = viewModel(target)
        vm.setScene("Cactus", cloud)
        vm.onIntent(ExportIntent.Select(ExportFormat.SPLAT))
        vm.onIntent(ExportIntent.Share)
        advanceUntilIdle()
        assertArrayEquals(SplatCodec.write(cloud), target.shared.getValue("Cactus.splat").toByteArray())
        assertEquals(
            ShareRequest("content://app.exports/exports/Cactus.splat", "Cactus.splat"),
            vm.uiState.value.share,
        )
        vm.onIntent(ExportIntent.ShareLaunched)
        assertNull(vm.uiState.value.share)
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
