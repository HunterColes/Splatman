package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.core.utils.MainDispatcherExtension
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatFileException
import com.huntercoles.splatman.viewer.presentation.load.SplatLoader
import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.viewer.rendering.RoomStart
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun scene(name: String, count: Int = 3, sizeBytes: Long? = null): LoadedScene {
        val cloud = TestClouds.cloud(count)
        val framing = SceneFraming.of(cloud.positions, cloud.count, 60f)
        return LoadedScene(
            name, SplatFormat.PLY, cloud, RenderScene(SplatPacker().pack(cloud), cloud.positions, framing.radius),
            framing, shDropped = false, sizeBytes = sizeBytes,
        )
    }

    /** The Library's rows by scan id; records renames and deletes. */
    private class FakeScans(val rows: MutableMap<String, ScanRecord> = mutableMapOf()) : ViewerScanStore {
        val renamed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        override suspend fun get(scanId: String): ScanRecord? = rows[scanId]

        override suspend fun rename(scanId: String, name: String) {
            renamed += scanId to name
        }

        override suspend fun delete(scanId: String) {
            deleted += scanId
        }
    }

    private class FakeHints(
        override var hintSeen: Boolean = false,
        override var roomHintSeen: Boolean = false,
    ) : ViewerHintStore {
        override fun markHintSeen() {
            hintSeen = true
        }

        override fun markRoomHintSeen() {
            roomHintSeen = true
        }
    }

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

    private val settings = SettingsPreferences(MapStore())

    private fun viewModel(
        loader: SplatLoader,
        scanId: String? = null,
        scans: ViewerScanStore = FakeScans(),
        hints: ViewerHintStore = FakeHints(),
        imported: Boolean? = null,
        args: SavedStateHandle = SavedStateHandle(
            buildMap {
                if (scanId != null) put(ViewerViewModel.ARG_SCAN_ID, scanId)
                if (imported != null) put(ViewerViewModel.ARG_IMPORTED, imported)
            },
        ),
    ): ViewerViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ViewerViewModel(loader, scans, hints, settings, args) as T
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
        assertEquals(ViewerLoad.Failed(sample, "Not enough memory to open this file.", outOfMemory = true), vm.uiState.value.load)
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

    private val scanId = "0b7e-scan"
    private val scanSource = SplatSource.Scan(scanId)
    private val record = ScanRecord("Desk plant", createdAtMs = 1_700_000_000_000, keyframeCount = 42, sizeBytes = 1234)

    private fun TestScope.loadedScan(
        scans: FakeScans = FakeScans(mutableMapOf(scanId to record)),
        hints: FakeHints = FakeHints(),
    ): ViewerViewModel {
        val vm = viewModel(FakeLoader(mapOf(scanSource to { scene(scanId) })), scanId, scans, hints)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a loaded scan maps to its Library name, details and edit actions`() = test {
        val state = loadedScan().uiState.value
        assertEquals("Desk plant", state.title)
        assertTrue(state.canEdit)
        assertEquals(
            ViewerDetails("Desk plant", 1_700_000_000_000, 3, 1234, 42, SplatFormat.PLY),
            state.details,
        )
        assertTrue(state.chromeVisible)
        assertFalse(state.autoRotate)
    }

    @Test
    fun `a sample maps to its file name, shows as imported and cannot be renamed or deleted`() = test {
        val vm = viewModel(FakeLoader(mapOf(sample to { scene("Tape file") })))
        vm.onIntent(ViewerIntent.Open(sample))
        assertEquals(sample.name, vm.uiState.value.title)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("Tape file", state.title)
        assertFalse(state.canEdit)
        assertEquals(null, state.details?.frames)
        vm.onIntent(ViewerIntent.StartRename)
        vm.onIntent(ViewerIntent.AskDelete)
        assertEquals(null, vm.uiState.value.rename)
        assertFalse(vm.uiState.value.confirmDelete)
    }

    @Test
    fun `a failed load keeps the source name as title and offers no details`() = test {
        val doc = SplatSource.Document("content://x/1", "broken.ply")
        val vm = viewModel(FakeLoader(mapOf(doc to { throw SplatFileException("bad") })))
        vm.onIntent(ViewerIntent.Open(doc))
        advanceUntilIdle()
        assertEquals("broken.ply", vm.uiState.value.title)
        vm.onIntent(ViewerIntent.ShowDetails)
        assertFalse(vm.uiState.value.showDetails)
    }

    @Test
    fun `rename opens prefilled, saves the trimmed name and updates the title`() = test {
        val scans = FakeScans(mutableMapOf(scanId to record))
        val vm = loadedScan(scans)
        vm.onIntent(ViewerIntent.StartRename)
        assertEquals(RenameDialog("Desk plant"), vm.uiState.value.rename)
        vm.onIntent(ViewerIntent.RenameChanged("  Kitchen fern  "))
        vm.onIntent(ViewerIntent.SaveRename)
        advanceUntilIdle()
        assertEquals(listOf(scanId to "Kitchen fern"), scans.renamed)
        assertEquals("Kitchen fern", vm.uiState.value.title)
        assertEquals("Kitchen fern", vm.uiState.value.details?.name)
        assertEquals(null, vm.uiState.value.rename)
    }

    @Test
    fun `rename refuses a blank name and keeps the dialog open`() = test {
        val scans = FakeScans(mutableMapOf(scanId to record))
        val vm = loadedScan(scans)
        vm.onIntent(ViewerIntent.StartRename)
        vm.onIntent(ViewerIntent.RenameChanged("   "))
        assertFalse(vm.uiState.value.rename!!.canSave)
        vm.onIntent(ViewerIntent.SaveRename)
        advanceUntilIdle()
        assertTrue(scans.renamed.isEmpty())
        assertEquals("Desk plant", vm.uiState.value.title)
        assertEquals(RenameDialog("   "), vm.uiState.value.rename)
    }

    @Test
    fun `rename clips at 80 characters and shows the counter past 60`() = test {
        val vm = loadedScan()
        vm.onIntent(ViewerIntent.StartRename)
        vm.onIntent(ViewerIntent.RenameChanged("a".repeat(60)))
        assertFalse(vm.uiState.value.rename!!.showCounter)
        vm.onIntent(ViewerIntent.RenameChanged("a".repeat(61)))
        assertTrue(vm.uiState.value.rename!!.showCounter)
        vm.onIntent(ViewerIntent.RenameChanged("b".repeat(100)))
        assertEquals("b".repeat(RenameRules.MAX_LENGTH), vm.uiState.value.rename!!.text)
        assertTrue(vm.uiState.value.rename!!.canSave)
    }

    @Test
    fun `rename rules keep one line and reject blanks`() {
        assertEquals("a b", RenameRules.clip("a\nb"))
        assertFalse(RenameRules.canSave(""))
        assertFalse(RenameRules.canSave(" \t "))
        assertTrue(RenameRules.canSave(" x "))
    }

    @Test
    fun `cancel closes the rename dialog without saving`() = test {
        val scans = FakeScans(mutableMapOf(scanId to record))
        val vm = loadedScan(scans)
        vm.onIntent(ViewerIntent.StartRename)
        vm.onIntent(ViewerIntent.RenameChanged("Other"))
        vm.onIntent(ViewerIntent.CancelRename)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.rename)
        assertTrue(scans.renamed.isEmpty())
        assertEquals("Desk plant", vm.uiState.value.title)
    }

    @Test
    fun `delete asks first, then deletes the scan and closes the viewer`() = test {
        val scans = FakeScans(mutableMapOf(scanId to record))
        val vm = loadedScan(scans)
        vm.onIntent(ViewerIntent.ConfirmDelete)
        advanceUntilIdle()
        assertTrue(scans.deleted.isEmpty(), "no delete without the confirm dialog")
        vm.onIntent(ViewerIntent.AskDelete)
        assertTrue(vm.uiState.value.confirmDelete)
        vm.onIntent(ViewerIntent.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf(scanId), scans.deleted)
        assertTrue(vm.uiState.value.closed)
    }

    private val roomStart = RoomStart(Vec3(1f, 1.5f, -2f), Vec3(0f, 0f, -1f))

    @Test
    fun `#293 a room scan carries its start pose and the room flag, an object scan neither`() = test {
        val room = loadedScan(FakeScans(mutableMapOf(scanId to record.copy(room = roomStart)))).uiState.value
        assertEquals(roomStart, room.room)
        assertTrue(room.details!!.room)

        store.clear()
        val objectScan = loadedScan().uiState.value
        assertNull(objectScan.room)
        assertFalse(objectScan.details!!.room)
    }

    @Test
    fun `#293 a sample never opens as a room`() = test {
        val vm = viewModel(FakeLoader(mapOf(sample to { scene("s") })))
        vm.onIntent(ViewerIntent.Open(sample))
        advanceUntilIdle()
        assertNull(vm.uiState.value.room)
        assertFalse(vm.uiState.value.details!!.room)
    }

    @Test
    fun `#293 the room hint is shown and remembered apart from the object hint`() = test {
        val hints = FakeHints(hintSeen = true)
        val scans = FakeScans(mutableMapOf(scanId to record.copy(room = roomStart)))
        val vm = loadedScan(scans, hints)
        assertTrue(vm.uiState.value.showHint)
        vm.onIntent(ViewerIntent.DismissHint)
        assertTrue(hints.roomHintSeen)
        store.clear()
        assertFalse(loadedScan(scans, hints).uiState.value.showHint)

        val fresh = FakeHints(roomHintSeen = true)
        store.clear()
        assertTrue(loadedScan(hints = fresh).uiState.value.showHint)
    }

    @Test
    fun `the gesture hint shows on the first open only and a dismiss is remembered`() = test {
        val hints = FakeHints()
        val vm = loadedScan(hints = hints)
        assertTrue(vm.uiState.value.showHint)
        vm.onIntent(ViewerIntent.DismissHint)
        assertFalse(vm.uiState.value.showHint)
        assertTrue(hints.hintSeen)

        store.clear()
        val again = loadedScan(hints = hints)
        assertFalse(again.uiState.value.showHint)
    }

    @Test
    fun `tap toggles the chrome and the auto-rotate button toggles auto-rotate`() = test {
        val vm = loadedScan()
        vm.onIntent(ViewerIntent.ToggleChrome)
        assertFalse(vm.uiState.value.chromeVisible)
        vm.onIntent(ViewerIntent.ToggleChrome)
        assertTrue(vm.uiState.value.chromeVisible)
        vm.onIntent(ViewerIntent.ToggleAutoRotate)
        assertTrue(vm.uiState.value.autoRotate)
    }

    @Test
    fun `details open and close only once a scene is loaded`() = test {
        val vm = loadedScan()
        vm.onIntent(ViewerIntent.ShowDetails)
        assertTrue(vm.uiState.value.showDetails)
        vm.onIntent(ViewerIntent.HideDetails)
        assertFalse(vm.uiState.value.showDetails)
    }

    @Test
    fun `#186 a crop reload reopens the source through Loading, keeps the title and the info sheet shows the new count`() =
        test {
            val scans = FakeScans(mutableMapOf(scanId to record))
            var count = 3
            val vm = viewModel(FakeLoader(mapOf(scanSource to { scene(scanId, count) })), scanId, scans)
            advanceUntilIdle()
            assertEquals(null, vm.uiState.value.reloaded)

            // The crop store rewrote splat.ply and the Library recounted it.
            count = 2
            scans.rows[scanId] = record.copy(sizeBytes = 600)
            vm.onIntent(ViewerIntent.Reload(ViewerReload.CROPPED))

            assertEquals(ViewerLoad.Loading(scanSource, ViewerReload.CROPPED), vm.uiState.value.load)
            assertEquals("Desk plant", vm.uiState.value.title)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(ViewerReload.CROPPED, state.reloaded)
            assertEquals(2, (state.load as ViewerLoad.Loaded).scene.count)
            assertEquals(ViewerDetails("Desk plant", 1_700_000_000_000, 2, 600, 42, SplatFormat.PLY), state.details)

            vm.onIntent(ViewerIntent.ReloadShown)
            assertEquals(null, vm.uiState.value.reloaded)
        }

    @Test
    fun `#250 a sample and a picked file show their file size, and a crop reload shows the new size`() = test {
        val doc = SplatSource.Document("content://picked/y", "Y")
        var size = 6_000_000L
        val vm = viewModel(
            FakeLoader(mapOf(sample to { scene("Cactus", sizeBytes = size) }, doc to { scene("Y", sizeBytes = 900) })),
        )
        vm.onIntent(ViewerIntent.Open(sample))
        advanceUntilIdle()
        assertEquals(6_000_000L, vm.uiState.value.details?.sizeBytes)

        size = 2_500_000L
        vm.onIntent(ViewerIntent.Reload(ViewerReload.CROPPED))
        advanceUntilIdle()
        assertEquals(2_500_000L, vm.uiState.value.details?.sizeBytes)

        vm.onIntent(ViewerIntent.Open(doc))
        advanceUntilIdle()
        assertEquals(900L, vm.uiState.value.details?.sizeBytes)
    }

    @Test
    fun `#250 a scan keeps the Library's size over its file's`() = test {
        val loader = FakeLoader(mapOf(scanSource to { scene(scanId, sizeBytes = 99) }))
        val vm = viewModel(loader, scanId, FakeScans(mutableMapOf(scanId to record)))
        advanceUntilIdle()
        assertEquals(1234L, vm.uiState.value.details?.sizeBytes)
    }

    @Test
    fun `#186 a revert reload is marked so the screen shows crop_reverting`() = test {
        val vm = loadedScan()
        vm.onIntent(ViewerIntent.Reload(ViewerReload.REVERTED))
        assertEquals(ViewerLoad.Loading(scanSource, ViewerReload.REVERTED), vm.uiState.value.load)
        advanceUntilIdle()
        assertEquals(ViewerReload.REVERTED, vm.uiState.value.reloaded)
    }

    @Test
    fun `#186 every load that reaches Ready is told once, failures are not`() = test {
        val doc = SplatSource.Document("content://gone/9")
        val vm = viewModel(
            FakeLoader(mapOf(scanSource to { scene(scanId) }, doc to { throw FileNotFoundException("gone") })),
            scanId,
        )
        val ready = mutableListOf<SplatSource>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ready.collect { ready += it } }
        advanceUntilIdle()
        vm.onIntent(ViewerIntent.Reload(ViewerReload.CROPPED))
        advanceUntilIdle()
        vm.onIntent(ViewerIntent.Open(doc))
        advanceUntilIdle()
        assertEquals(listOf<SplatSource>(scanSource, scanSource), ready)
    }

    @Test
    fun `#186 entering crop stops auto-rotate, dismisses the hint and shows the chrome`() = test {
        val hints = FakeHints()
        val vm = loadedScan(hints = hints)
        vm.onIntent(ViewerIntent.ToggleAutoRotate)
        vm.onIntent(ViewerIntent.ToggleChrome)
        assertTrue(vm.uiState.value.showHint)

        vm.onIntent(ViewerIntent.EnterCrop)

        val state = vm.uiState.value
        assertFalse(state.autoRotate)
        assertFalse(state.showHint)
        assertTrue(state.chromeVisible)
        assertTrue(hints.hintSeen)
    }

    @Test
    fun `#196 a scan the import flow opened carries imp_done with its name once`() = test {
        val vm = viewModel(
            FakeLoader(mapOf(scanSource to { scene(scanId) })),
            scanId,
            FakeScans(mutableMapOf(scanId to record)),
            imported = true,
        )
        assertNull(vm.uiState.value.importDone)
        advanceUntilIdle()
        assertEquals("Desk plant", vm.uiState.value.importDone)

        vm.onIntent(ViewerIntent.ImportShown)
        assertNull(vm.uiState.value.importDone)
        vm.onIntent(ViewerIntent.Reload(ViewerReload.CROPPED))
        advanceUntilIdle()
        assertNull(vm.uiState.value.importDone)
    }

    @Test
    fun `#196 a normal open of a scan carries no imp_done`() = test {
        assertNull(loadedScan().uiState.value.importDone)
        val vm = viewModel(FakeLoader(mapOf(scanSource to { scene(scanId) })), scanId, imported = false)
        advanceUntilIdle()
        assertNull(vm.uiState.value.importDone)
    }

    @Test
    fun `#196 a recreated Viewer does not show imp_done again`() = test {
        val args = SavedStateHandle(mapOf(ViewerViewModel.ARG_SCAN_ID to scanId, ViewerViewModel.ARG_IMPORTED to true))
        val loader = FakeLoader(mapOf(scanSource to { scene(scanId) }))
        viewModel(loader, scanId, args = args)
        advanceUntilIdle()
        store.clear()
        val again = viewModel(loader, scanId, args = args)
        advanceUntilIdle()
        assertNull(again.uiState.value.importDone)
    }
}
