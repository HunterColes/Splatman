package com.huntercoles.splatman.capture.tracking

import kotlinx.coroutines.flow.Flow

/**
 * The tracking seam of ADR 0004. One consumer collects [frames]; it owns every [TrackedFrame] it receives and must
 * [TrackedFrame.close] it. Only the ARCore implementation (`tracking/arcore/`) imports the ARCore library.
 */
interface PoseSource {
    /** Frames while started and in the foreground; a frame the consumer never receives is closed by the source. */
    val frames: Flow<TrackedFrame>

    /** Checks availability and permission, then opens and resumes the tracker; never throws for a missing tracker. */
    suspend fun start()

    /** Pauses and closes the tracker; [start] may be called again. */
    fun stop()
}
