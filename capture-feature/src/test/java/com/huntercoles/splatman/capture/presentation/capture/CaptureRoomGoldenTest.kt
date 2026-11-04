package com.huntercoles.splatman.capture.presentation.capture

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.recording.RoomCells
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.splat.capture.ScanMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Room mode's goldens (#291): Aim with the Object/Room switch (Room selected, no reticle, the room aim copy, the
 * Camera ready pill), the pose-free Seen gauge mid-scan (half fills, `estimate` under the percent), the large view
 * (the bigger ring only, no path on the pose-free path), and Aim at 360 dp and 200% font (the switch drops its icons
 * and the hint goes); light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class CaptureRoomGoldenTest {

    @get:Rule
    val golden = ScreenGolden()

    private val aim = CaptureUiState(
        phase = CapturePhase.Preview(TrackingHint.TRACKING, paused = false),
        stage = CaptureStage.AIM,
        status = StatusPill.READY,
        instruction = Instruction.ROOM_AIM,
        poseFree = true,
        mode = ScanMode.ROOM,
        modeSwitchVisible = true,
        roomCells = RoomCells(),
    )

    // Walls half the way round, a little floor: Seen only, as the gyro gives it.
    private val seen = CoverageCells(low = 0b0011_1100, level = 0b1111_1111_1111_0000, high = 0)

    private val capturing = aim.copy(
        recorder = RecorderState.Recording(
            keyframes = 48,
            coverage = seen.fraction,
            seedPoints = 0,
            droppedFrames = 0,
            cells = seen,
        ),
        stage = CaptureStage.CAPTURING,
        status = StatusPill.RECORDING,
        instruction = Instruction.ROOM_WALK,
        cells = seen,
        facingSegment = 6,
        elapsedMs = 63_000,
        framesKept = 48,
        modeSwitchVisible = false,
        roomCells = RoomCells(seen = seen),
        roomPercent = seen.percent,
    )

    private fun capture(screen: String, state: CaptureUiState, dark: Boolean, large: Boolean = false, font: Float = 1f) =
        golden.capture(screen, dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                Surface(modifier = Modifier.fillMaxSize(), color = SplatColors.ViewerBackground) {
                    CaptureOverlay(state, fontScale = font, onIntent = {}, onHelp = {}, largeRingOpen = large)
                }
            }
        }

    @Test
    fun aimLight() = capture("CaptureRoomAim", aim, dark = false)

    @Test
    fun aimDark() = capture("CaptureRoomAim", aim, dark = true)

    @Test
    fun gaugeLight() = capture("CaptureRoomGauge", capturing, dark = false)

    @Test
    fun gaugeDark() = capture("CaptureRoomGauge", capturing, dark = true)

    @Test
    fun largeViewLight() = capture("CaptureRoomLarge", capturing, dark = false, large = true)

    @Test
    fun largeViewDark() = capture("CaptureRoomLarge", capturing, dark = true, large = true)

    @Test
    fun aimLargeFontLight() = capture("CaptureRoomAimFont200", aim, dark = false, font = 2f)

    @Test
    fun aimLargeFontDark() = capture("CaptureRoomAimFont200", aim, dark = true, font = 2f)

    // Object mode at 360 dp and 200% font: Finish keeps its label on one line, its icon dropped (#380).
    private val objectAim = aim.copy(mode = ScanMode.OBJECT, poseFree = false, instruction = Instruction.AIM)

    @Test
    fun objectAimLargeFontLight() = capture("CaptureObjectAimFont200", objectAim, dark = false, font = 2f)

    @Test
    fun objectAimLargeFontDark() = capture("CaptureObjectAimFont200", objectAim, dark = true, font = 2f)
}
