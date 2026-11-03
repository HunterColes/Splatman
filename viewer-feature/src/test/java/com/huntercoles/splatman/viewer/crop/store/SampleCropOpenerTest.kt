package com.huntercoles.splatman.viewer.crop.store

import android.content.Context
import android.content.res.AssetManager
import com.huntercoles.splatman.viewer.presentation.load.AndroidSplatOpener
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [AndroidSplatOpener] prefers a sample's cropped copy in `filesDir/sample-crops/` over the asset. */
class SampleCropOpenerTest {

    @TempDir
    lateinit var filesDir: File

    private val sample = SplatSource.Sample("samples/cactus-sh1.ply", "Cactus")
    private val assets = mockk<AssetManager> {
        every { open(sample.assetPath) } answers { "asset".byteInputStream() }
    }
    private val context = mockk<Context> {
        every { filesDir } answers { this@SampleCropOpenerTest.filesDir }
        every { this@mockk.assets } returns this@SampleCropOpenerTest.assets
    }
    private val opener = AndroidSplatOpener(context)

    private fun read() = opener.open(sample).use { it.readBytes().decodeToString() }

    @Test
    fun `the asset opens when there is no crop`() {
        assertEquals("asset", read())
    }

    @Test
    fun `the crop opens instead of the asset, and the asset again once it is gone`() {
        val crop = File(File(filesDir, CropPaths.SAMPLE_CROPS_DIR).apply { mkdirs() }, "cactus-sh1.ply")
        crop.writeText("crop")
        assertEquals("crop", read())

        crop.delete()
        assertEquals("asset", read())
    }

    @Test
    fun `a stale tmp is not opened`() {
        File(File(filesDir, CropPaths.SAMPLE_CROPS_DIR).apply { mkdirs() }, "cactus-sh1.ply.tmp").writeText("tmp")
        assertEquals("asset", read())
    }
}
