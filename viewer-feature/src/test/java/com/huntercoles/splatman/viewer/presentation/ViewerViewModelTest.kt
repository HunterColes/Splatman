package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatFileException
import com.huntercoles.splatman.viewer.presentation.load.SplatLoader
import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.FileNotFoundException

class ViewerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension(dispatcher)

    private val sample = SplatSamples.ALL.first()
    private val store = ViewModelStore()

    /** Answers each source from [answers]; a missing source suspends until cancelled. */
    private class FakeLoader(val answers: Map<SplatSource, () -> LoadedScene>) : SplatLoader {
        val started = mutableListOf<SplatSource>()
        val cancelled = mutableListOf<SplatSource>()

        override suspend fun load(source: SplatSource): LoadedScene {
            started += source
            val answer = answers[source] ?: try {
                awaitCancellation()
            } finally {
                cancelled += source
            }
            return answer()
        }
    }

    private fun scene(name: String): LoadedScene {
        val cloud = TestClouds.cloud(3)
        val framing = SceneFraming.of(cloud.positions, cloud.count, 60f)
        return LoadedScene(
            name, SplatFormat.PLY, cloud, RenderScene(SplatPacker().pack(cloud), cloud.positions, framing.radius),
            framing, shDropped = false,
        )
    }

    private fun viewModel(loader: SplatLoader, scanId: String? = null): ViewerViewModel {
        val args = SavedStateHandle(if (scanId == null) emptyMap() else mapOf(ViewerViewModel.ARG_SCAN_ID to scanId))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ViewerViewModel(loader, args) as T
        }
        return ViewModelProvider(store, factory)[ViewerViewModel::class.java]
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test
    fun `starts idle with the bundled samples offered`() = test {
        val state = viewModel(FakeLoader(emptyMap())).uiState.value
        assertSame(ViewerLoad.Idle, state.load)
        assertEquals(SplatSamples.ALL, state.samples)
        assertTrue(SplatSamples.ALL.all { it.assetPath.startsWith("samples/") && it.assetPath.endsWith(".ply") })
    }

    @Test
    fun `opening a sample shows loading, then the loaded scene`() = test {
        val loaded = scene("tape")
        val vm = viewModel(FakeLoader(mapOf(sample to { loaded })))
        vm.onIntent(ViewerIntent.Open(sample))
        assertEquals(ViewerLoad.Loading(sample), vm.uiState.value.load)
        advanceUntilIdle()
        val load = vm.uiState.value.load
        assertInstanceOf(ViewerLoad.Loaded::class.java, load)
        assertSame(loaded, (load as ViewerLoad.Loaded).scene)
    }

    @Test
    fun `an unreadable file ends in an error with the decoder's message`() = test {
        val doc = SplatSource.Document("content://picked/1", "broken.ply")
        val vm = viewModel(FakeLoader(mapOf(doc to { throw SplatFileException("Not a splat file (PLY or .splat).") })))
        vm.onIntent(ViewerIntent.Open(doc))
        advanceUntilIdle()
        assertEquals(ViewerLoad.Failed(doc, "Not a splat file (PLY or .splat)."), vm.uiState.value.load)
    }

    @Test
    fun `a file that cannot be opened ends in a read error`() = test {
        val doc = SplatSource.Document("content://gone/2")
        val vm = viewModel(FakeLoader(mapOf(doc to { throw FileNotFoundException("gone") })))
        vm.onIntent(ViewerIntent.Open(doc))
        advanceUntilIdle()
        assertEquals(ViewerLoad.Failed(doc, "The file could not be read."), vm.uiState.value.load)
    }

    @Test
    fun `running out of memory ends in an error, not a crash`() = test {
        val vm = viewModel(FakeLoader(mapOf(sample to { throw OutOfMemoryError("1M splats") })))
        vm.onIntent(ViewerIntent.Open(sample))
        advanceUntilIdle()
        assertEquals(ViewerLoad.Failed(sample, "Not enough memory to open this file."), vm.uiState.value.load)
    }

    @Test
    fun `leaving the screen cancels a load in flight`() = test {
        val loader = FakeLoader(emptyMap())
        val vm = viewModel(loader)
        vm.onIntent(ViewerIntent.Open(sample))
        advanceUntilIdle()
        assertEquals(listOf(sample), loader.started)
        assertTrue(loader.cancelled.isEmpty())

        store.clear()
        advanceUntilIdle()
        assertEquals(listOf(sample), loader.cancelled)
        assertEquals(ViewerLoad.Loading(sample), vm.uiState.value.load)
    }

    @Test
    fun `a new open cancels the previous one and only the newest result lands`() = test {
        val second = SplatSamples.ALL.last()
        val gate = CompletableDeferred<Unit>()
        val loaded = scene("cactus")
        val loader = object : SplatLoader {
            var firstCancelled = false
            override suspend fun load(source: SplatSource): LoadedScene {
                if (source == second) {
                    gate.await()
                    return loaded
                }
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled = true
                }
            }
        }
        val vm = viewModel(loader)
        vm.onIntent(ViewerIntent.Open(SplatSource.Document("content://slow/3")))
        advanceUntilIdle()
        vm.onIntent(ViewerIntent.Open(second))
        advanceUntilIdle()
        assertTrue(loader.firstCancelled)
        assertEquals(ViewerLoad.Loading(second), vm.uiState.value.load)
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(loaded, (vm.uiState.value.load as ViewerLoad.Loaded).scene)
    }

    @Test
    fun `retry opens the last source again`() = test {
        var calls = 0
        val loaded = scene("tape")
        val vm = viewModel(
            FakeLoader(mapOf(sample to { if (calls++ == 0) throw SplatFileException("flaky") else loaded })),
        )
        vm.onIntent(ViewerIntent.Open(sample))
        advanceUntilIdle()
        assertInstanceOf(ViewerLoad.Failed::class.java, vm.uiState.value.load)
        vm.onIntent(ViewerIntent.Retry)
        advanceUntilIdle()
        assertSame(loaded, (vm.uiState.value.load as ViewerLoad.Loaded).scene)
    }

    @Test
    fun `a sample id from the Library opens that sample at once`() = test {
        val loaded = scene("tape")
        val tape = SplatSamples.ALL.first { it.assetPath == "samples/measuring-tape.ply" }
        val vm = viewModel(FakeLoader(mapOf(tape to { loaded })), scanId = "sample:measuring-tape.ply")
        assertEquals(ViewerLoad.Loading(tape), vm.uiState.value.load)
        advanceUntilIdle()
        assertSame(loaded, (vm.uiState.value.load as ViewerLoad.Loaded).scene)
    }

    @Test
    fun `a scan id opens that scan's splat at once`() = test {
        val loaded = scene("scan")
        val scan = SplatSource.Scan("0b7e-scan")
        val vm = viewModel(FakeLoader(mapOf(scan to { loaded })), scanId = "0b7e-scan")
        assertEquals(ViewerLoad.Loading(scan), vm.uiState.value.load)
        advanceUntilIdle()
        assertSame(loaded, (vm.uiState.value.load as ViewerLoad.Loaded).scene)
    }

    @Test
    fun `scan ids name a plain folder and reject paths, samples and blanks`() {
        assertEquals(SplatSource.Scan("scan_20261102"), SplatSource.Scan.fromScanId("scan_20261102"))
        assertEquals(null, SplatSource.Scan.fromScanId("../secret"))
        assertEquals(null, SplatSource.Scan.fromScanId("a/b"))
        assertEquals(null, SplatSource.Scan.fromScanId(".hidden"))
        assertEquals(null, SplatSource.Scan.fromScanId("sample:cactus-sh1.ply"))
        assertEquals(null, SplatSource.Scan.fromScanId(" "))
        assertEquals(null, SplatSource.Scan.fromScanId(null))
    }

    @Test
    fun `sample ids map to asset paths and reject anything outside samples`() {
        assertEquals(SplatSamples.ALL.last(), SplatSamples.fromScanId("sample:cactus-sh1.ply"))
        assertEquals(SplatSource.Sample("samples/cactus.spz", "Cactus"), SplatSamples.fromScanId("sample:cactus.spz"))
        assertEquals(null, SplatSamples.fromScanId("sample:../secret.ply"))
        assertEquals(null, SplatSamples.fromScanId("sample:"))
        assertEquals(null, SplatSamples.fromScanId("scan-1"))
        assertEquals(null, SplatSamples.fromScanId(null))
    }

    @Test
    fun `the stats overlay starts hidden and the first tap shows it`() = test {
        val vm = viewModel(FakeLoader(emptyMap()))
        assertFalse(vm.uiState.value.showStats)
        vm.onIntent(ViewerIntent.ToggleStats)
        assertTrue(vm.uiState.value.showStats)
        vm.onIntent(ViewerIntent.ToggleStats)
        assertFalse(vm.uiState.value.showStats)
    }
}
