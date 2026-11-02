package com.huntercoles.splatman.splat.train

import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.packet.TrainingInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeedTrainerTest {

    @Test
    fun `emits once with the seed cloud`() {
        val seeds = SeedPoints(2, floatArrayOf(0f, 0f, 0f, 0.01f, 0f, 0f), ByteArray(6) { 0x7F })
        val trainer: Trainer = SeedTrainer()
        val emissions = runBlocking { trainer.train(TrainingInput(emptyList(), seeds)).toList() }
        assertEquals(1, emissions.size)
        val last = emissions.single()
        val cloud = checkNotNull(last.snapshot)
        assertEquals(2, cloud.count)
        assertEquals(2, last.splatCount)
        assertEquals(0, last.iteration)
        assertEquals(0, last.totalIterations)
    }

    @Test
    fun `empty seeds emit an empty cloud`() {
        val seeds = SeedPoints(0, FloatArray(0), ByteArray(0))
        val last = runBlocking { SeedTrainer().train(TrainingInput(emptyList(), seeds)).toList() }.single()
        assertEquals(0, last.snapshot?.count)
    }
}
