package com.huntercoles.splatman.capture.presentation.capture

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.core.design.SplatColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capture's golden (#67): the guidance overlay mid-scan (tracking, a third of the ring covered, 42 s in), drawn as
 * CaptureScreen draws it over the camera, light and dark. The camera feed is ARCore's, which the JVM has none of, so
 * the overlay sits on the screen's own background where the preview would be.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class CaptureScreenGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val cells = CoverageCells(low = 0, level = 0b0000_1111_1100, high = 0)

    private val state = CaptureUiState(
        phase = CapturePhase.Preview(TrackingHint.TRACKING, paused = false),
        recorder = RecorderState.Recording(keyframes = 24, coverage = 0.25f, seedPoints = 4_096, droppedFrames = 0, cells = cells),
        stage = CaptureStage.CAPTURING,
        status = StatusPill.TRACKING,
        instruction = Instruction.WALK,
        subjectSet = true,
        cells = cells,
        facingSegment = 6,
        elapsedMs = 42_000,
        framesKept = 24,
    )

    private fun capture(dark: Boolean) = golden.capture("CaptureScreen", dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = SplatColors.ViewerBackground) {
            CaptureOverlay(state, fontScale = 1f, onIntent = {}, onHelp = {})
        }
    }

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)
}
