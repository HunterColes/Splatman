package com.huntercoles.splatman.capture.recording

import com.huntercoles.splatman.capture.presentation.capture.GuidanceWarning
import com.huntercoles.splatman.capture.preview.PreviewTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** What a [PoseFreeRecorder] is doing. */
sealed interface PoseFreeState {
    /** Nothing open. */
    data object Idle : PoseFreeState

    /** Opening the camera and sensors. */
    data object Opening : PoseFreeState

    /** The camera runs; frames are measured for [warnings], none kept. */
    data class Previewing(val warnings: Set<GuidanceWarning> = emptySet()) : PoseFreeState

    /**
     * Taking keyframes: [coverage] is the covered share of [cells] (gyro coverage), [droppedFrames] frames skipped
     * because the worker was busy.
     */
    data class Recording(
        val keyframes: Int,
        val coverage: Float,
        val cells: CoverageCells,
        val warnings: Set<GuidanceWarning>,
        val droppedFrames: Int,
    ) : PoseFreeState

    /** Finishing the capture of [keyframes] keyframes and moving it into `scans/`. */
    data class Saving(val keyframes: Int) : PoseFreeState

    /** The capture is in `scans/[scanId]/`. */
    data class Saved(val scanId: String, val keyframes: Int) : PoseFreeState

    /** The camera was refused or lost, or the capture could not be written; nothing new in `scans/`. */
    data class Failed(val reason: String) : PoseFreeState
}

/**
 * Records a scan without poses (epic #199, ADR 0007): camera frames, keyframes picked as they arrive and written at
 * once, `imu.csv` and `calibration.json`, moved into `scans/<id>/` on [stop]. Bound only in the foss build: the full
 * build injects an empty `Optional<PoseFreeRecorder>` and records through ARCore instead. Call everything from one
 * thread (the UI's).
 */
interface PoseFreeRecorder {
    /** What the recorder is doing; updated after every frame while previewing or recording. */
    val state: StateFlow<PoseFreeState>

    /** Opens the camera and the sensors: [PoseFreeState.Previewing], or false (and [PoseFreeState.Failed]) on refusal. */
    suspend fun open(): Boolean

    /** Shows the camera on [target], or stops showing it (null); the job ends once the camera uses it. */
    fun setPreview(target: PreviewTarget?): Job

    /** Background (false) pauses frames and sensors, recording or not; foreground (true) resumes them. */
    fun setForeground(inForeground: Boolean)

    /** Starts a recording; false while not previewing (closed, recording or saving). */
    fun start(): Boolean

    /** While recording, [paused] true keeps no keyframes (the sensors keep recording); false takes them again. */
    fun pause(paused: Boolean)

    /** Ends the recording and saves it: [PoseFreeState.Saving], then Saved or Failed. The job ends with the save. */
    fun stop(): Job

    /** Abandons the recording or a save under way: nothing of it stays on disk. */
    fun cancel(): Job

    /** The owner is gone: closes the camera and sensors, abandons a recording; a save under way still finishes. */
    fun release()
}
