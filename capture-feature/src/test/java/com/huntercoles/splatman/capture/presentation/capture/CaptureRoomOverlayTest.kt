package com.huntercoles.splatman.capture.presentation.capture

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.recording.CoverageBand
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RoomCells
import com.huntercoles.splatman.capture.recording.RoomSide
import com.huntercoles.splatman.core.design.SplatTheme
import com.huntercoles.splatman.splat.capture.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Room mode overlay (#291): the switch, the room copy, the gauge, its large view and the room help tips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)
class CaptureRoomOverlayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<CaptureIntent>()

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

    private val walls = CoverageCells(level = (1 shl 24) - 1)

    private val capturing = aim.copy(
        stage = CaptureStage.CAPTURING,
        status = StatusPill.RECORDING,
        instruction = Instruction.ROOM_WALK,
        cells = walls,
        modeSwitchVisible = false,
        roomCells = RoomCells(seen = walls),
        roomPercent = walls.percent,
    )

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** The gauge's description with every wall Seen: 33 percent, the ceiling missing all the way round. */
    private fun wallsSeen(): String =
        text(R.string.cap_ring_missing, 33, text(R.string.cap_room_band_ceiling), text(R.string.cap_room_side_all))

    private fun show(state: CaptureUiState) {
        compose.setContent {
            SplatTheme(darkTheme = true) { CaptureOverlay(state, fontScale = 1f, onIntent = { intents += it }, onHelp = {}) }
        }
    }

    @Test
    fun aimInRoom_showsTheSwitchWithRoomSelected_theRoomAimCopy_andTheCameraReadyPill() {
        show(aim)
        compose.onNodeWithText(text(R.string.cap_mode_room)).assertIsSelected().assertIsEnabled()
        compose.onNodeWithText(text(R.string.cap_mode_object)).assertIsNotSelected()
        compose.onNodeWithContentDescription(text(R.string.cap_mode_label)).assertExists()
        compose.onNodeWithText(text(R.string.cap_room_aim)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cap_room_aim_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cap_status_recording_only)).assertIsDisplayed()
    }

    @Test
    fun tappingObject_selectsTheMode() {
        show(aim)
        compose.onNodeWithText(text(R.string.cap_mode_object)).performClick()
        assertEquals(listOf<CaptureIntent>(CaptureIntent.SelectMode(ScanMode.OBJECT)), intents)
    }

    @Test
    fun finding_showsTheSwitchDisabled() {
        show(aim.copy(stage = CaptureStage.FINDING_SURFACES, instruction = Instruction.FIND_SURFACE))
        compose.onNodeWithText(text(R.string.cap_mode_room)).assertIsNotEnabled()
    }

    @Test
    fun objectAim_poseFree_showsTheSwitchWithObjectSelected_andTheReadyPill() {
        show(aim.copy(mode = ScanMode.OBJECT, instruction = Instruction.AIM, roomCells = null))
        compose.onNodeWithText(text(R.string.cap_mode_object)).assertIsSelected()
        compose.onNodeWithText(text(R.string.cap_status_ready)).assertIsDisplayed()
    }

    @Test
    fun pausedAfterTheFirstKeptFrame_hasNoSwitch() {
        show(capturing.copy(stage = CaptureStage.PAUSED, instruction = Instruction.PAUSED, status = StatusPill.PAUSED))
        compose.onNodeWithText(text(R.string.cap_mode_room)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.cap_mode_label)).assertDoesNotExist()
    }

    @Test
    fun trackedCapture_hasNoSwitch() {
        show(aim.copy(poseFree = false, mode = ScanMode.OBJECT, instruction = Instruction.AIM, roomCells = null))
        compose.onNodeWithText(text(R.string.cap_mode_room)).assertDoesNotExist()
    }

    @Test
    fun roomGauge_describesTheSeenCellsInRoomWords_withTheEstimateLine() {
        show(capturing)
        compose.onNodeWithContentDescription(wallsSeen()).assertExists()
        compose.onNodeWithText(text(R.string.cap_room_estimate), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.cap_room_walk)).assertIsDisplayed()
    }

    @Test
    fun tappingTheGauge_opensTheLargeView_andBackClosesIt() {
        show(capturing)
        val description = wallsSeen()
        compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
        compose.onNodeWithContentDescription(description).performClick()
        compose.onAllNodesWithContentDescription(description).assertCountEquals(2)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
    }

    @Test
    fun noGyroscope_hidesTheGauge_andSaysWhy() {
        show(capturing.copy(noGauge = true))
        compose.onNodeWithText(text(R.string.cap_room_no_gauge)).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("percent covered", substring = true)).assertCountEquals(0)
    }

    @Test
    fun budgetReached_saysThatIsAllTheScanCanUse() {
        show(capturing.copy(instruction = Instruction.ROOM_FULL, budgetReached = true, ready = true))
        compose.onNodeWithText(text(R.string.cap_room_full)).assertIsDisplayed()
    }

    @Test
    fun roomHelp_listsTheThreeRoomTips() {
        compose.setContent { SplatTheme(darkTheme = true) { Tips(room = true) } }
        for (tip in listOf(R.string.cap_room_tip_1, R.string.cap_room_tip_2, R.string.cap_room_tip_3)) {
            compose.onNodeWithText(text(tip)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.onb_tip_slow)).assertDoesNotExist()
    }

    @Test
    fun roomInstructions_useTheRoomCopy() {
        val expected = mapOf(
            Instruction.ROOM_AIM to R.string.cap_room_aim,
            Instruction.ROOM_WALK to R.string.cap_room_walk,
            Instruction.ROOM_WALK_ON to R.string.cap_room_walk_on,
            Instruction.ROOM_LOOK_UP to R.string.cap_room_look_up,
            Instruction.ROOM_LOOK_DOWN to R.string.cap_room_look_down,
            Instruction.ROOM_FULL to R.string.cap_room_full,
        )
        for ((instruction, id) in expected) assertEquals(instruction.name, id, instructionText(instruction, true, false))
        assertEquals(R.string.cap_room_no_gauge, instructionText(Instruction.ROOM_WALK, poseFree = true, noGauge = true))
        assertEquals(R.string.cap_room_walk_on, instructionText(Instruction.ROOM_WALK_ON, poseFree = true, noGauge = true))
        assertEquals(R.string.cap_room_aim_hint, instructionHint(Instruction.ROOM_AIM, poseFree = true))
        assertNull(instructionHint(Instruction.ROOM_WALK, poseFree = true))
    }

    @Test
    fun roomCells_fillHalfWhenSeen_andFullWhenCovered() {
        val cells = RoomCells(
            seen = CoverageCells(level = 0b11),
            covered = CoverageCells(level = 0b10),
        )
        assertEquals(RingFill.HALF, roomFill(cells, CoverageBand.LEVEL, 0))
        assertEquals(RingFill.FULL, roomFill(cells, CoverageBand.LEVEL, 1))
        assertEquals(RingFill.NONE, roomFill(cells, CoverageBand.LEVEL, 2))
        assertEquals(RingFill.NONE, roomFill(cells, CoverageBand.HIGH, 0))
    }

    @Test
    fun poseFreeDescription_countsSeen_whilePosesCountOnlyCovered() {
        val cells = RoomCells(seen = walls)
        val estimate = roomGaugeDescription(cells, percent = walls.percent, facing = 0, estimate = true)
        assertEquals(33, estimate.percent)
        assertEquals(CoverageBand.HIGH, estimate.missing?.band)
        assertEquals(RoomSide.ALL_ROUND, estimate.missing?.side)
        val tracked = roomGaugeDescription(cells, percent = 0, facing = 0, estimate = false)
        assertEquals(CoverageBand.HIGH, tracked.missing?.band)
        // With poses, Seen-only walls are still missing: the whole ring is one missing arc per band.
        assertEquals(0, tracked.percent)
    }

    @Test
    fun modeSwitch_showsOnlyBeforeTheTake() {
        assertEquals(true, modeSwitchShown(aim))
        assertEquals(true, modeSwitchShown(aim.copy(stage = CaptureStage.FINDING_SURFACES)))
        assertEquals(false, modeSwitchShown(aim.copy(stage = CaptureStage.STARTING)))
        assertEquals(false, modeSwitchShown(capturing))
        assertEquals(false, modeSwitchShown(capturing.copy(stage = CaptureStage.PAUSED)))
        assertEquals(false, modeSwitchShown(aim.copy(poseFree = false)))
    }
}
