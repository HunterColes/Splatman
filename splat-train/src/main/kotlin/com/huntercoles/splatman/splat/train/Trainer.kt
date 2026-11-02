package com.huntercoles.splatman.splat.train

import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.init.SeedInit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Turns a [TrainingInput] into a splat (ADR 0005). The flow is cold and does its work in the collector's context,
 * so the caller picks the dispatcher. The last emission carries the final cloud in [TrainingProgress.snapshot].
 */
interface Trainer {
    fun train(input: TrainingInput): Flow<TrainingProgress>
}

/**
 * Stage 1 only (ADR 0005): the seed splat from [SeedInit], one emission with the final cloud. No iterations run,
 * so `iteration` and `totalIterations` are 0 and `loss` is NaN (nothing was rendered to measure it).
 */
class SeedTrainer : Trainer {
    override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
        val start = System.nanoTime()
        val cloud = SeedInit.fromSeeds(input.seedPoints)
        emit(
            TrainingProgress(
                iteration = 0,
                totalIterations = 0,
                loss = Float.NaN,
                splatCount = cloud.count,
                elapsedMs = (System.nanoTime() - start) / 1_000_000,
                snapshot = cloud,
            ),
        )
    }
}
