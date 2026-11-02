package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.train.CpuTrainer
import com.huntercoles.splatman.splat.train.SeedTrainer
import com.huntercoles.splatman.splat.train.Trainer

/**
 * Which [Trainer] the runner uses (ADR 0005): [CpuTrainer] by default; the debug flag [PREF_SEED_TRAINER] (a boolean
 * in the [PREFS] shared preferences) picks [SeedTrainer] instead.
 */
internal object TrainerChoice {
    const val PREFS = "splatman_debug"
    const val PREF_SEED_TRAINER = "seed_trainer"

    fun trainer(seedOnly: Boolean): Trainer = if (seedOnly) SeedTrainer() else CpuTrainer()
}
