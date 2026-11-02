package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning.CLOSE
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning.DARK
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning.FAR
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning.FAST
import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning.LOST
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.tracking.TrackingFailure
import com.huntercoles.splatman.capture.tracking.TrackingState
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuidanceTest {
    private val arbiter = WarningArbiter(minShowMs = 1500, cooldownMs = 4000)

    @Test
    fun `the highest-priority live warning shows`() {
        assertEquals(listOf(LOST, DARK, FAST, CLOSE, FAR), GuidanceWarning.entries)
        assertEquals(DARK, arbiter.update(setOf(FAR, DARK, CLOSE), 0))
    }

    @Test
    fun `a higher warning replaces a lower one at once`() {
        assertEquals(FAR, arbiter.update(setOf(FAR), 0))
        assertEquals(LOST, arbiter.update(setOf(FAR, LOST), 100))
    }

    @Test
    fun `a lower warning never replaces a higher one before the minimum show`() {
        assertEquals(DARK, arbiter.update(setOf(DARK), 0))
        // Dark cleared at once; Too far is live but must wait until Dark has shown 1.5 s.
        assertEquals(DARK, arbiter.update(setOf(FAR), 200))
        assertEquals(DARK, arbiter.update(setOf(FAR), 1499))
        assertEquals(FAR, arbiter.update(setOf(FAR), 1500))
    }

    @Test
    fun `a warning that cleared stays for the minimum show, then hides`() {
        assertEquals(FAST, arbiter.update(setOf(FAST), 0))
        assertEquals(FAST, arbiter.update(emptySet(), 100))
        assertEquals(FAST, arbiter.update(emptySet(), 1499))
        assertNull(arbiter.update(emptySet(), 1500))
    }

    @Test
    fun `a live warning stays as long as it is live`() {
        assertEquals(FAR, arbiter.update(setOf(FAR), 0))
        assertEquals(FAR, arbiter.update(setOf(FAR), 10_000))
    }

    @Test
    fun `the same warning waits the cooldown before it shows again`() {
        assertEquals(FAST, arbiter.update(setOf(FAST), 0))
        assertNull(arbiter.update(emptySet(), 2000)) // hidden at 2000
        assertNull(arbiter.update(setOf(FAST), 2100))
        assertNull(arbiter.update(setOf(FAST), 5999))
        assertEquals(FAST, arbiter.update(setOf(FAST), 6000))
    }

    @Test
    fun `a warning in its cooldown lets a lower one show`() {
        assertEquals(CLOSE, arbiter.update(setOf(CLOSE), 0))
        assertNull(arbiter.update(emptySet(), 1500))
        assertEquals(FAR, arbiter.update(setOf(CLOSE, FAR), 1600))
    }

    @Test
    fun `a replaced warning starts its cooldown when it is replaced`() {
        assertEquals(FAR, arbiter.update(setOf(FAR), 0))
        assertEquals(LOST, arbiter.update(setOf(LOST, FAR), 100)) // Too far hidden at 100
        assertEquals(LOST, arbiter.update(setOf(FAR), 1000))
        assertNull(arbiter.update(setOf(FAR), 1600)) // Lost shown 1.5 s; Too far still cooling down
        assertEquals(FAR, arbiter.update(setOf(FAR), 4100))
    }

    @Test
    fun `reset hides and forgets cooldowns`() {
        arbiter.update(setOf(FAST), 0)
        arbiter.update(emptySet(), 1500)
        arbiter.reset()
        assertEquals(FAST, arbiter.update(setOf(FAST), 1600))
    }

    @Test
    fun `live warnings come from tracking, motion, light and distance`() {
        val ok = FrameSignals(TrackingState.TRACKING, TrackingFailure.NONE, 10f, 0.2f, 120f, 0.6f)
        assertEquals(emptySet<GuidanceWarning>(), liveWarnings(ok))
        assertEquals(setOf(LOST), liveWarnings(ok.copy(trackingState = TrackingState.PAUSED)))
        assertEquals(
            setOf(DARK),
            liveWarnings(ok.copy(trackingState = TrackingState.PAUSED, failure = TrackingFailure.INSUFFICIENT_LIGHT)),
        )
        assertEquals(
            setOf(FAST),
            liveWarnings(ok.copy(trackingState = TrackingState.PAUSED, failure = TrackingFailure.EXCESSIVE_MOTION)),
        )
        assertEquals(setOf(FAST), liveWarnings(ok.copy(rotationDegPerS = 80f)))
        assertEquals(setOf(FAST), liveWarnings(ok.copy(speedMPerS = 1.5f)))
        assertEquals(setOf(DARK), liveWarnings(ok.copy(meanLuma = 20f)))
        assertEquals(setOf(CLOSE), liveWarnings(ok.copy(subjectDistanceM = 0.1f)))
        assertEquals(setOf(FAR), liveWarnings(ok.copy(subjectDistanceM = 3f)))
        assertEquals(emptySet<GuidanceWarning>(), liveWarnings(ok.copy(meanLuma = null, subjectDistanceM = null)))
    }

    @Test
    fun `motion meter measures rotation and speed, smoothed`() {
        val m = MotionMeter()
        val turn = { deg: Float -> Quat.fromAxisAngle(Vec3(0f, 1f, 0f), Math.toRadians(deg.toDouble()).toFloat()) }
        m.add(CameraPose(turn(0f), Vec3.ZERO), 0)
        // 10 degrees and 10 cm in 0.1 s: 100 deg/s and 1 m/s, of which the meter takes 30 %.
        m.add(CameraPose(turn(10f), Vec3(0.1f, 0f, 0f)), 100_000_000)
        assertEquals(30f, m.rotationDegPerS, 0.05f)
        assertEquals(0.3f, m.speedMPerS, 1e-4f)
        // A gap over 0.5 s restarts it.
        m.add(CameraPose(turn(90f), Vec3(5f, 0f, 0f)), 1_000_000_000)
        assertEquals(0f, m.rotationDegPerS)
    }

    @Test
    fun `capturing instructions walk, then higher, then lower, then ready`() {
        val all = (1 shl Coverage.SEGMENTS) - 1
        assertEquals(Instruction.WALK, capturingInstruction(CoverageCells(level = 0b1111)))
        assertEquals(Instruction.GO_HIGHER, capturingInstruction(CoverageCells(level = (1 shl 18) - 1)))
        // Started high: the level band below is next.
        assertEquals(Instruction.GO_LOWER, capturingInstruction(CoverageCells(high = (1 shl 18) - 1)))
        assertEquals(Instruction.READY, capturingInstruction(CoverageCells(level = all, high = 0b11111)))
    }

    @Test
    fun `stages, pills and instructions follow the spec's table`() {
        val idle = RecorderState.Idle
        val recording = RecorderState.Recording(0, 0f, 0, 0)
        assertEquals(CaptureStage.STARTING, captureStage(idle, false, hasFrame = false, TrackingHint.TRACKING))
        assertEquals(
            CaptureStage.FINDING_SURFACES,
            captureStage(idle, false, true, TrackingHint.LOOKING_FOR_SURFACES),
        )
        assertEquals(CaptureStage.AIM, captureStage(idle, false, true, TrackingHint.TRACKING))
        assertEquals(CaptureStage.CAPTURING, captureStage(recording, false, true, TrackingHint.TRACKING))
        assertEquals(CaptureStage.PAUSED, captureStage(recording, true, true, TrackingHint.TRACKING))
        assertEquals(CaptureStage.FINISHING, captureStage(RecorderState.Saving(3), false, true, TrackingHint.TRACKING))

        assertEquals(StatusPill.STARTING, statusPill(CaptureStage.STARTING, TrackingHint.TRACKING))
        assertEquals(StatusPill.LIMITED, statusPill(CaptureStage.FINDING_SURFACES, TrackingHint.LOOKING_FOR_SURFACES))
        assertEquals(StatusPill.LOST, statusPill(CaptureStage.CAPTURING, TrackingHint.TRACKING_LOST))
        assertEquals(StatusPill.PAUSED, statusPill(CaptureStage.PAUSED, TrackingHint.TRACKING))

        val cells = CoverageCells()
        assertEquals(Instruction.AIM, instructionFor(CaptureStage.AIM, cells, null, idle))
        assertEquals(Instruction.WALK, instructionFor(CaptureStage.CAPTURING, cells, null, recording))
        assertNull(instructionFor(CaptureStage.CAPTURING, cells, FAST, recording)) // the warning replaces it
        assertEquals(Instruction.PAUSED, instructionFor(CaptureStage.PAUSED, cells, null, recording))
        assertEquals(
            Instruction.NOTHING_RECORDED,
            instructionFor(CaptureStage.AIM, cells, null, RecorderState.NothingRecorded),
        )
    }
}
