package com.huntercoles.splatman.splat.train

import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.init.SeedInit
import com.huntercoles.splatman.splat.train.optim.OptimSettings
import com.huntercoles.splatman.splat.train.optim.SplatOptimizer
import com.huntercoles.splatman.splat.train.optim.TrainingFailedException
import com.huntercoles.splatman.splat.train.raster.Rasterizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stage 1 then stage 2 (ADR 0005). The first emission is the seed splat at iteration 0 (the fallback); then
 * [SplatOptimizer] runs `config.iterations` iterations with a snapshot every [OptimSettings.snapshotEvery] and at the
 * end, so the last emission carries the final cloud. With 0 iterations the seed emission is the only one.
 *
 * Cold and run in the collector's context; cancellation is checked between iterations. A failed run (the cloud
 * pruned to nothing, a non-finite parameter, no keyframes) ends the flow with [TrainingFailedException] carrying the
 * iteration. [seed] fixes the keyframe draws and split samples; [threads] sizes the rasteriser.
 */
class CpuTrainer(
    private val threads: Int = Runtime.getRuntime().availableProcessors(),
    private val seed: Long = 0L,
    private val settings: OptimSettings = OptimSettings(),
) : Trainer {
    override fun train(input: TrainingInput): Flow<TrainingProgress> = flow {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        val total = input.config.iterations
        val seedCloud = SeedInit.fromSeeds(input.seedPoints)
        emit(TrainingProgress(0, total, Float.NaN, seedCloud.count, elapsedMs(), seedCloud))
        if (total == 0) return@flow
        if (input.keyframes.isEmpty()) throw TrainingFailedException(0, "no keyframes to train on")
        Rasterizer(threads).use { rasterizer ->
            val optimizer = SplatOptimizer(
                seedCloud, input.keyframes, total, input.config.maxSplats, rasterizer, settings, seed,
            )
            while (optimizer.iteration < total) {
                currentCoroutineContext().ensureActive()
                optimizer.step()
                val t = optimizer.iteration
                if (t % settings.snapshotEvery == 0 || t == total) {
                    val snapshot = optimizer.snapshot()
                    emit(TrainingProgress(t, total, optimizer.loss, snapshot.count, elapsedMs(), snapshot))
                }
            }
        }
    }
}
