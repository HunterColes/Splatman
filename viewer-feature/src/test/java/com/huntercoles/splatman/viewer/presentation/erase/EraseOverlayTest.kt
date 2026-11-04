package com.huntercoles.splatman.viewer.presentation.erase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.splat.edit.EraseMask
import com.huntercoles.splatman.viewer.presentation.ViewerBottomBar
import com.huntercoles.splatman.viewer.presentation.ViewerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #332: the erase chrome's states as the user sees them (docs/design/screens/erase.md "States", "Accessibility").
 * Native graphics: without it, taps on the unselected segments of the size picker never land (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h780dp-port-xhdpi")
class EraseOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val mask = EraseMask(2_000)
    private val brush = EraseBrushSize.MEDIUM
    private val intents = mutableListOf<EraseIntent>()
    private var closed = 0

    private fun show(state: EraseUiState, session: EraseSession = EraseSession()) {
        compose.setContent {
            ViewerTheme {
                EraseOverlay(
                    state = state,
                    session = session,
                    haptics = false,
                    talkBack = false,
                    onIntent = { intents += it },
                    onClose = { closed++ },
                )
            }
        }
    }

    @Test
    fun `nothing marked shows the hint, no count, and Undo and Apply off`() {
        show(EraseUiState.NothingMarked(brush, mask))
        compose.onNodeWithText("Paint over what to remove. Two fingers to turn.").assertExists()
        compose.onNodeWithText("splats marked", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Undo last stroke").assertIsNotEnabled()
        compose.onNodeWithText("Remove isolated splats").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun `marked shows the grouped count and the marked hint, and Apply sends Apply`() {
        show(EraseUiState.Marked(1_240, canUndo = true, brush, mask))
        compose.onNodeWithText("1,240 splats marked").assertExists()
        compose.onNodeWithText("Pink splats get removed. Undo or keep painting.").assertExists()
        compose.onNodeWithContentDescription("Undo last stroke").assertIsEnabled().performClick()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(listOf(EraseIntent.Undo, EraseIntent.Apply), intents)
    }

    @Test
    fun `one marked splat reads singular`() {
        show(EraseUiState.Marked(1, canUndo = true, brush, mask))
        compose.onNodeWithText("1 splat marked").assertExists()
    }

    @Test
    fun `picking a size sends it and Close is labelled`() {
        show(EraseUiState.NothingMarked(brush, mask))
        compose.onNodeWithText("Large").performClick()
        compose.onNodeWithContentDescription("Close erase").performClick()
        assertEquals(listOf<EraseIntent>(EraseIntent.SetBrushSize(EraseBrushSize.LARGE)), intents)
        assertEquals(1, closed)
    }

    @Test
    fun `the TalkBack path Remove isolated is a labelled button that sends RemoveIsolated`() {
        val session = EraseSession()
        show(EraseUiState.NothingMarked(brush, mask), session)
        compose.onNodeWithText("Remove isolated splats").performClick()
        assertEquals(listOf<EraseIntent>(EraseIntent.RemoveIsolated), intents)
    }

    @Test
    fun `finding isolated reads its running label and leaves only Cancel`() {
        val session = EraseSession().apply { isolating = true }
        show(EraseUiState.Painting(0, canUndo = false, brush, mask), session)
        compose.onNodeWithText("Finding isolated splats…").assertExists()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Medium").assertIsNotEnabled()
    }

    @Test
    fun `applying replaces the rows with progress`() {
        show(EraseUiState.Applying(1_240, brush, mask))
        compose.onNodeWithText("Removing splats…").assertExists()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Remove isolated splats").assertDoesNotExist()
    }

    @Test
    fun `refusals show their row`() {
        show(EraseUiState.Refused(2_000, canUndo = true, brush, mask, EraseMessage.ALL_MARKED))
        compose.onNodeWithText("Every splat is marked. Undo some and try again.").assertExists()
    }

    @Test
    fun `a save error shows its row`() {
        show(EraseUiState.Refused(10, canUndo = true, brush, mask, EraseMessage.SAVE_ERROR))
        compose.onNodeWithText("Couldn't save. Check free space and try again.").assertExists()
    }

    @Composable
    private fun Bar(fontScale: Float) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ViewerTheme {
                Box(Modifier.fillMaxSize().background(SplatColors.ViewerBackground)) {
                    ViewerBottomBar(
                        autoRotate = false,
                        showStats = false,
                        onReset = {},
                        onAutoRotate = {},
                        onStats = {},
                        onShare = {},
                        canCrop = true,
                        canErase = true,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h900dp-port-xhdpi")
    fun `the bar offers Erase, and Share keeps its label when it fits`() {
        compose.setContent { Bar(fontScale = 1f) }
        compose.onNodeWithContentDescription("Erase").assertIsEnabled()
        compose.onNodeWithText("Share").assertExists()
    }

    @Test
    fun `at large text Share becomes an icon button and the icons keep their place`() {
        compose.setContent { Bar(fontScale = 2f) }
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share").assertIsEnabled()
        compose.onNodeWithContentDescription("Erase").assertIsEnabled()
    }
}
