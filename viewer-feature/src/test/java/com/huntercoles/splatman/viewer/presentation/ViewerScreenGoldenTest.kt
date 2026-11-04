package com.huntercoles.splatman.viewer.presentation

import androidx.lifecycle.SavedStateHandle
import com.huntercoles.splatman.core.preferences.PreferenceStore
import com.huntercoles.splatman.core.preferences.SettingsPreferences
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.viewer.crop.store.CropStore
import com.huntercoles.splatman.viewer.export.ExportTarget
import com.huntercoles.splatman.viewer.export.ExportUseCase
import com.huntercoles.splatman.viewer.export.ExportViewModel
import com.huntercoles.splatman.viewer.presentation.crop.CropViewModel
import com.huntercoles.splatman.viewer.presentation.erase.EraseViewModel
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.SplatLoader
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.OutputStream

/**
 * The Viewer's golden (#67): a loaded scene with its chrome and the first-run gesture hint, light and dark. The
 * splats themselves are drawn by GLES, which Robolectric does not run, so the golden holds the chrome over the
 * viewer background.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class ViewerScreenGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val source = SplatSource.Document("content://picked/kitchen.ply", "Kitchen table")

    private fun scene(): LoadedScene {
        val cloud = TestClouds.cloud(3)
        val framing = SceneFraming.of(cloud.positions, cloud.count, 60f)
        return LoadedScene(
            "Kitchen table", SplatFormat.PLY, cloud,
            RenderScene(SplatPacker().pack(cloud), cloud.positions, framing.radius), framing, shDropped = false,
        )
    }

    private fun capture(dark: Boolean) {
        val loaded = scene()
        val settings = SettingsPreferences(MapStore())
        val viewer = ViewerViewModel(OneScene(loaded), NoScans, FirstRun(), settings, SavedStateHandle())
        viewer.onIntent(ViewerIntent.Open(source))
        val export = ExportViewModel(ExportUseCase(Dispatchers.Unconfined), NoExport, settings)
        val crop = CropViewModel(NoCrops, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val erase = EraseViewModel(NoCrops, Dispatchers.Unconfined, Dispatchers.Unconfined)
        golden.capture("ViewerScreen", dark) {
            ViewerScreen(
                onBack = {}, viewModel = viewer, exportViewModel = export, cropViewModel = crop,
                eraseViewModel = erase,
            )
        }
    }

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)

    private class OneScene(private val scene: LoadedScene) : SplatLoader {
        override suspend fun load(source: SplatSource): LoadedScene = scene
    }

    private object NoScans : ViewerScanStore {
        override suspend fun get(scanId: String): ScanRecord? = null

        override suspend fun rename(scanId: String, name: String) = Unit

        override suspend fun delete(scanId: String) = Unit
    }

    private class FirstRun(
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

    private object NoCrops : CropStore {
        override suspend fun apply(source: SplatSource, cloud: SplatCloud) = error("not used")

        override suspend fun revert(source: SplatSource) = error("not used")

        override suspend fun hasOriginal(source: SplatSource): Boolean = false
    }

    /** Settings at their defaults. */
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

    private object NoExport : ExportTarget {
        override fun openDocument(uri: String): OutputStream = error("not used")

        override fun createInFolder(treeUri: String, fileName: String): String = error("not used")

        override fun deleteDocument(uri: String) = Unit

        override fun clearShareFiles() = Unit

        override fun freeBytes(): Long? = null

        override fun openShareFile(fileName: String): OutputStream = error("not used")

        override fun shareUri(fileName: String): String = error("not used")
    }
}
