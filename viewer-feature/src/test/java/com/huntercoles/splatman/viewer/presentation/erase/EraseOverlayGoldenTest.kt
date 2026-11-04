package com.huntercoles.splatman.viewer.presentation.erase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.splat.edit.EraseMask
import com.huntercoles.splatman.viewer.presentation.ScreenGolden
import com.huntercoles.splatman.viewer.presentation.ViewerBottomBar
import com.huntercoles.splatman.viewer.presentation.ViewerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Erase mode's goldens (#332, docs/design/screens/erase.md): idle (the Viewer bar offering Erase), nothing marked
 * (entry), and marked (count pill, a stroke trail under the brush ring). The splats are GLES, not drawn here. Erase is
 * always dark, so the light-theme goldens must match the dark ones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class EraseOverlayGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val mask = EraseMask(2_000)

    @Composable
    private fun Render(content: @Composable BoxScope.() -> Unit) {
        ViewerTheme {
            Box(Modifier.fillMaxSize().background(SplatColors.ViewerBackground)) { content() }
        }
    }

    private fun idle(dark: Boolean) = golden.capture("EraseIdle", dark) {
        Render {
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

    private fun nothingMarked(dark: Boolean) = golden.capture("EraseNothingMarked", dark) {
        Render {
            EraseOverlay(
                state = EraseUiState.NothingMarked(EraseBrushSize.MEDIUM, mask),
                session = EraseSession(),
                haptics = false,
                talkBack = false,
                onIntent = {},
                onClose = {},
            )
        }
    }

    private fun marked(dark: Boolean) {
        val session = EraseSession().apply {
            begin(Offset(220f, 600f))
            to(Offset(320f, 640f))
            to(Offset(440f, 620f))
        }
        golden.capture("EraseMarked", dark) {
            Render {
                EraseOverlay(
                    state = EraseUiState.Marked(1_240, canUndo = true, EraseBrushSize.MEDIUM, mask),
                    session = session,
                    haptics = false,
                    talkBack = false,
                    onIntent = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun idleLight() = idle(dark = false)

    @Test
    fun idleDark() = idle(dark = true)

    @Test
    fun nothingMarkedLight() = nothingMarked(dark = false)

    @Test
    fun nothingMarkedDark() = nothingMarked(dark = true)

    @Test
    fun markedLight() = marked(dark = false)

    @Test
    fun markedDark() = marked(dark = true)
}
