package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.FailureReason
import com.huntercoles.splatman.capture.processing.FailureReasons
import com.huntercoles.splatman.capture.processing.LowStorageException
import com.huntercoles.splatman.capture.processing.NoFramesException
import com.huntercoles.splatman.capture.processing.ProcessingNotice
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.splat.packet.TrainingProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException

class ProcessingStagesTest {

    private fun progress(iteration: Int, total: Int = 1000) = TrainingProgress(iteration, total, 0f, 1, 0L)

    private fun assertAt(stage: ProcessingStage, overall: Float, state: ProcessingState) {
        val p = state.stageProgress()!!
        assertEquals(stage, p.stage, "$state")
        assertEquals(overall, p.overall, 1e-4f, "$state")
    }

    @Test
    fun `stages are four, in order, and their weights fill the bar`() {
        assertEquals(4, ProcessingStage.COUNT)
        assertEquals(listOf(1, 2, 3, 4), ProcessingStage.entries.map { it.step })
        assertEquals(1f, ProcessingStage.entries.sumOf { it.weight.toDouble() }.toFloat(), 1e-6f)
    }

    @Test
    fun `loading frames is Preparing, across its share`() {
        assertAt(ProcessingStage.PREPARE, 0f, ProcessingState.Running(null))
        assertAt(ProcessingStage.PREPARE, 0.05f, ProcessingState.Running(null, prepared = 0.5f))
        assertAt(ProcessingStage.PREPARE, 0.10f, ProcessingState.Running(null, prepared = 1f))
    }

    @Test
    fun `iterations up to 60 percent are Building, the rest Refining`() {
        assertAt(ProcessingStage.BUILD, 0.10f, ProcessingState.Running(progress(0), 1f))
        assertAt(ProcessingStage.BUILD, 0.375f, ProcessingState.Running(progress(300), 1f))
        assertAt(ProcessingStage.REFINE, 0.65f, ProcessingState.Running(progress(600), 1f))
        assertAt(ProcessingStage.REFINE, 0.80f, ProcessingState.Running(progress(800), 1f))
        assertAt(ProcessingStage.REFINE, 0.95f, ProcessingState.Running(progress(1000), 1f))
    }

    @Test
    fun `saving is the last stage, paused keeps its place, a seed-only run counts as trained`() {
        assertAt(ProcessingStage.SAVE, 0.95f, ProcessingState.Running(progress(1000), 1f, saving = true))
        assertAt(ProcessingStage.BUILD, 0.375f, ProcessingState.Paused(progress(300)))
        assertAt(ProcessingStage.REFINE, 0.95f, ProcessingState.Running(progress(0, total = 0), 1f))
        assertAt(ProcessingStage.REFINE, 0.95f, ProcessingState.Running(progress(2000), 1f))
    }

    @Test
    fun `nothing running has no stage, and percent is whole`() {
        assertNull(ProcessingState.Idle.stageProgress())
        assertNull(ProcessingState.Cancelled.stageProgress())
        assertNull(ProcessingState.Failed("x").stageProgress())
        assertNull(ProcessingState.Done(File("s")).stageProgress())
        assertEquals(37, StageProgress(ProcessingStage.BUILD, 0.375f).percent())
        assertEquals(100, StageProgress(ProcessingStage.SAVE, 1f).percent())
    }

    @Test
    fun `failure reason maps to its copy key`() {
        assertEquals(R.string.proc_failed_tracking, FailureReason.TRACKING.copy())
        assertEquals(R.string.proc_failed_frames, FailureReason.FRAMES.copy())
        assertEquals(R.string.proc_failed_memory, FailureReason.MEMORY.copy())
        assertEquals(R.string.proc_failed_storage, FailureReason.STORAGE.copy())
        assertEquals(R.string.proc_failed_unknown, FailureReason.UNKNOWN.copy())
    }

    @Test
    fun `errors map to reasons`() {
        assertEquals(FailureReason.MEMORY, FailureReasons.of(OutOfMemoryError()))
        assertEquals(FailureReason.STORAGE, FailureReasons.of(LowStorageException(5)))
        assertEquals(FailureReason.STORAGE, FailureReasons.of(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FailureReason.FRAMES, FailureReasons.of(NoFramesException("none")))
        assertEquals(FailureReason.FRAMES, FailureReasons.of(IllegalArgumentException("no keyframes to train on")))
        assertEquals(FailureReason.MEMORY, FailureReasons.of(IOException("wrapped", OutOfMemoryError())))
        assertEquals(FailureReason.UNKNOWN, FailureReasons.of(IllegalStateException("diverged")))
        assertEquals(
            ProcessingState.Failed("x", FailureReason.STORAGE, 7),
            FailureReasons.failed(LowStorageException(7)).copy(message = "x"),
        )
    }

    @Test
    fun `the screen's failure line is Stopped after a cancel, the reason's copy after a failure`() {
        assertEquals(R.string.proc_failed_stopped, ProcessingUiState("a", ProcessingState.Cancelled).failureCopy)
        assertEquals(
            R.string.proc_failed_memory,
            ProcessingUiState("a", ProcessingState.Failed("oom", FailureReason.MEMORY)).failureCopy,
        )
        assertNull(ProcessingUiState("a", ProcessingState.Running(null)).failureCopy)
        assertEquals(false, ProcessingUiState("a", ProcessingState.Failed("x"), framesKept = false).canRetry)
        assertEquals(true, ProcessingUiState("a", ProcessingState.Failed("x")).canRetry)
    }

    @Test
    fun `the notification follows the state`() {
        assertEquals(ProcessingNotice.Running(null), ProcessingNotice.of("a", ProcessingState.Idle))
        assertEquals(ProcessingNotice.Running(37), ProcessingNotice.of("a", ProcessingState.Running(progress(300), 1f)))
        assertEquals(ProcessingNotice.Running(37, hot = true), ProcessingNotice.of("a", ProcessingState.Paused(progress(300))))
        assertEquals(ProcessingNotice.Done("viewer/a"), ProcessingNotice.of("a", ProcessingState.Done(File("s"))))
        assertEquals(ProcessingNotice.Failed("processing/a"), ProcessingNotice.of("a", ProcessingState.Failed("x")))
        assertEquals(ProcessingNotice.None, ProcessingNotice.of("a", ProcessingState.Cancelled))
    }
}
