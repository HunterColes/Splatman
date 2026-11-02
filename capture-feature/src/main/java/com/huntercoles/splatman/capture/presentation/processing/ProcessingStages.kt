package com.huntercoles.splatman.capture.presentation.processing

import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.splat.packet.TrainingProgress

/**
 * The four stages the screen names, in order, with their share of the overall bar. Building is the trainer's
 * densification window (ADR 0005: up to 60 % of the iterations), Refining the rest of the run.
 */
enum class ProcessingStage(val label: Int, val weight: Float) {
    PREPARE(R.string.proc_stage_prepare, 0.10f),
    BUILD(R.string.proc_stage_build, 0.55f),
    REFINE(R.string.proc_stage_refine, 0.30f),
    SAVE(R.string.proc_stage_save, 0.05f),
    ;

    /** 1-based, for "Step n of 4". */
    val step: Int get() = ordinal + 1

    /** The overall fraction at which this stage starts. */
    val start: Float get() = entries.take(ordinal).sumOf { it.weight.toDouble() }.toFloat()

    companion object {
        /** Stages in a run ("Step n of [COUNT]"). */
        val COUNT: Int = entries.size

        /** The share of the iterations that is Building (the densification window); the rest is Refining. */
        const val BUILD_SHARE: Float = 0.6f
    }
}

/** Where a run is: its [stage] and the [overall] 0..1 across all four stages. */
data class StageProgress(val stage: ProcessingStage, val overall: Float)

/** The stage and overall progress of a running or paused state; null when nothing runs. */
fun ProcessingState.stageProgress(): StageProgress? = when (this) {
    is ProcessingState.Running -> when {
        saving -> at(ProcessingStage.SAVE, 0f)
        progress == null -> at(ProcessingStage.PREPARE, prepared)
        else -> training(progress)
    }
    is ProcessingState.Paused -> training(progress)
    else -> null
}

private fun training(progress: TrainingProgress): StageProgress {
    val f = if (progress.totalIterations <= 0) {
        1f
    } else {
        (progress.iteration.toFloat() / progress.totalIterations).coerceIn(0f, 1f)
    }
    val build = ProcessingStage.BUILD_SHARE
    return if (f < build) {
        at(ProcessingStage.BUILD, f / build)
    } else {
        at(ProcessingStage.REFINE, (f - build) / (1f - build))
    }
}

private fun at(stage: ProcessingStage, within: Float): StageProgress =
    StageProgress(stage, (stage.start + stage.weight * within.coerceIn(0f, 1f)).coerceIn(0f, 1f))

/** The whole percent shown beside the bar and in the notification. */
fun StageProgress.percent(): Int = (overall * PERCENT).toInt().coerceIn(0, PERCENT)

private const val PERCENT = 100
