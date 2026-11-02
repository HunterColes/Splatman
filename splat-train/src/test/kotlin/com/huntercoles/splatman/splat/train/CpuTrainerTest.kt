package com.huntercoles.splatman.splat.train

import com.huntercoles.splatman.splat.model.Keyframe
import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.packet.CameraRefinement
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.optim.OptimSettings
import com.huntercoles.splatman.splat.train.optim.TestScenes
import com.huntercoles.splatman.splat.train.optim.TrainingFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CpuTrainerTest {

    private val k = TestScenes.intrinsics(32, fov = 0.5f)
    private val truth = TestScenes.cloud(20, seed = 3)
    private val keyframes = TestScenes.keyframes(truth, TestScenes.ring(4, radius = 2f), k)

    /** The true centres as seed points, all mid grey. */
    private val seeds = SeedPoints(truth.count, truth.positions.copyOf(), ByteArray(3 * truth.count) { 0x80.toByte() })

    private fun input(iterations: Int, seeds: SeedPoints = this.seeds, frames: List<Keyframe> = keyframes) =
        TrainingInput(frames, seeds, TrainingConfig(iterations = iterations))

    @Test
    fun `seed first, then a snapshot every 250 iterations and at the end`() {
        val emissions = runBlocking { CpuTrainer(threads = 2).train(input(600)).toList() }
        assertEquals(listOf(0, 250, 500, 600), emissions.map { it.iteration })
        assertTrue(emissions.all { it.totalIterations == 600 && it.snapshot != null })
        assertEquals(truth.count, emissions.first().splatCount)
        assertTrue(emissions.first().loss.isNaN())
        val last = emissions.last()
        assertEquals(last.splatCount, last.snapshot!!.count)
        val before = TestScenes.psnr(emissions.first().snapshot!!, keyframes)
        val after = TestScenes.psnr(last.snapshot!!, keyframes)
        assertTrue(after > before + 3.0, "PSNR %.2f dB from the seed's %.2f dB".format(after, before))
        assertTrue(last.loss.isFinite() && last.loss > 0f)
        assertNotSame(emissions[1].snapshot!!.positions, last.snapshot!!.positions, "snapshots are copies")
    }

    @Test
    fun `the same seed gives the same result`() {
        val a = runBlocking { CpuTrainer(threads = 1, seed = 9).train(input(60)).toList() }.last()
        val b = runBlocking { CpuTrainer(threads = 3, seed = 9).train(input(60)).toList() }.last()
        assertTrue(a.snapshot!!.positions.contentEquals(b.snapshot!!.positions))
        assertEquals(a.loss, b.loss)
    }

    @Test
    fun `the learned lens is reported with every optimiser emission, and not with the flag off`() {
        val on = runBlocking {
            CpuTrainer(threads = 1, settings = OptimSettings(cameraFreezeSteps = 10, snapshotEvery = 20)).train(input(40))
                .toList()
        }
        assertEquals(listOf(0, 20, 40), on.map { it.iteration })
        assertNull(on.first().camera, "the seed emission has trained nothing")
        val camera = on.last().camera!!
        assertNotEquals(CameraRefinement(1f, 0f), camera, "30 thawed iterations move the lens")
        val off = runBlocking {
            CpuTrainer(threads = 1, settings = OptimSettings(refineCamera = false, snapshotEvery = 20)).train(input(40))
                .toList()
        }
        assertTrue(off.all { it.camera == null })
    }

    @Test
    fun `zero iterations emit the seed splat only`() {
        val emissions = runBlocking { CpuTrainer().train(input(0)).toList() }
        assertEquals(1, emissions.size)
        assertEquals(truth.count, emissions.single().snapshot!!.count)
    }

    @Test
    fun `cancellation stops between iterations`() {
        val seen = mutableListOf<TrainingProgress>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CpuTrainer(threads = 1).train(input(100_000))
                    .onEach { seen += it; if (it.iteration >= 250) currentCoroutineContext().cancel() }
                    .toList()
            }
        }
        // Cancelled right after the 250 snapshot: the next iteration never ran, so no 500 snapshot either.
        assertEquals(listOf(0, 250), seen.map { it.iteration })
    }

    @Test
    fun `empty seeds end the flow with an error after the seed emission`() {
        val none = SeedPoints(0, FloatArray(0), ByteArray(0))
        val seen = mutableListOf<TrainingProgress>()
        val e = assertThrows(TrainingFailedException::class.java) {
            runBlocking { CpuTrainer().train(input(100, seeds = none)).onEach { seen += it }.toList() }
        }
        assertEquals(0, e.iteration)
        assertEquals(listOf(0), seen.map { it.splatCount })
    }

    @Test
    fun `no keyframes end the flow with an error`() {
        val e = assertThrows(TrainingFailedException::class.java) {
            runBlocking { CpuTrainer().train(input(100, frames = emptyList())).toList() }
        }
        assertEquals(0, e.iteration)
    }
}
