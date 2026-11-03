package com.huntercoles.splatman

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.core.design.SplatTheme
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import com.huntercoles.splatman.library.presentation.CardState
import com.huntercoles.splatman.library.presentation.LibraryCard
import com.huntercoles.splatman.library.presentation.ScanCardGrid
import com.huntercoles.splatman.library.presentation.ThumbnailDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Library cards (#176) at 360 dp: a done card with its thumbnail and "date · size", a building card at 42 %, a failed
 * card and an imported one; leaves `library-cards-phone.png` beside the smoke test's screenshots. Also the thumbnail
 * decoder's hardening on a real decoder: corrupt and empty files give no picture, a huge one decodes sampled down.
 */
@RunWith(AndroidJUnit4::class)
class LibraryCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val cacheDir: File get() = instrumentation.targetContext.cacheDir

    @Test
    fun gridShowsDoneBuildingFailedAndImportedCards() {
        val picture = File(cacheDir, "cards-thumbnail.png").also { writePng(it, THUMB_W, THUMB_H) }
        val cards = listOf(
            card("done", "Desk plant", CardState.Ready(1_000), sizeText = "38 MB")
                .copy(thumbnail = ScanThumbnail(picture.path, picture.lastModified())),
            card("building", "Scan 1 Nov", CardState.Processing(42)),
            card("failed", "Bike", CardState.Failed),
            card("imported", "Garden gnome", CardState.Ready(500), sizeText = "4.2 MB").copy(imported = true),
        )
        compose.setContent {
            SplatTheme {
                Box(Modifier.width(PHONE_DP.dp).fillMaxHeight()) { ScanCardGrid(cards) }
            }
        }

        compose.onNodeWithText("· 38 MB", substring = true).assertExists()
        compose.onNodeWithText("Building… 42%").assertExists()
        assertTrue(compose.onAllNodes(hasText("Couldn't build")).fetchSemanticsNodes().size >= 1)
        compose.onNodeWithText("Imported").assertExists()
        compose.onNodeWithText("· 4.2 MB", substring = true).assertExists()
        screenshot("library-cards-phone.png")
    }

    @Test
    fun corruptOrEmptyThumbnailIsNoPicture() {
        val corrupt = File(cacheDir, "corrupt.png").apply { writeBytes(ByteArray(CORRUPT_BYTES) { it.toByte() }) }
        val empty = File(cacheDir, "empty.png").apply { writeBytes(ByteArray(0)) }
        assertNull(ThumbnailDecoder.decode(corrupt, CARD_W, CARD_H))
        assertNull(ThumbnailDecoder.decode(empty, CARD_W, CARD_H))
    }

    @Test
    fun hugeThumbnailDecodesSampledDown() {
        val big = File(cacheDir, "big.png").also { writePng(it, BIG_W, BIG_H) }
        val bitmap = ThumbnailDecoder.decode(big, CARD_W, CARD_H)
        assertNotNull(bitmap)
        // 4000 x 5000 into 400 x 500: sampled at 1/8, so 500 x 625, never the full 80 MB of pixels.
        assertEquals(BIG_W / 8, bitmap!!.width)
        assertEquals(BIG_H / 8, bitmap.height)
        big.delete()
    }

    private fun card(id: String, name: String, state: CardState, sizeText: String = "") = LibraryCard(
        id = id,
        name = name,
        createdAtMs = CREATED_MS,
        state = state,
        target = if (state is CardState.Ready) OpenTarget.Viewer(id) else OpenTarget.Processing(id),
        sizeText = sizeText,
    )

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(PNG_R, PNG_G, PNG_B))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        shell("mkdir -p $DEVICE_DIR")
        shell("screencap -p $DEVICE_DIR/$name")
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private companion object {
        const val PHONE_DP = 360
        const val CREATED_MS = 1_793_500_000_000L
        const val THUMB_W = 400
        const val THUMB_H = 500
        const val CARD_W = 400
        const val CARD_H = 500
        const val BIG_W = 4_000
        const val BIG_H = 5_000
        const val CORRUPT_BYTES = 4_096
        const val PNG_R = 40
        const val PNG_G = 120
        const val PNG_B = 90
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
    }
}
