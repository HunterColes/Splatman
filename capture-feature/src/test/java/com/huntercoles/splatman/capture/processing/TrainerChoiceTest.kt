package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.train.CpuTrainer
import com.huntercoles.splatman.splat.train.SeedTrainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrainerChoiceTest {
    @Test
    fun `the runner trains with CpuTrainer by default`() {
        assertTrue(TrainerChoice.trainer(seedOnly = false) is CpuTrainer)
    }

    @Test
    fun `the debug flag picks SeedTrainer`() {
        assertTrue(TrainerChoice.trainer(seedOnly = true) is SeedTrainer)
    }
}
