package com.huntercoles.splatman.capture.processing

import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProcessingRunsTest {

    private val scan = File("scans/one")
    private val finished = mutableListOf<Pair<ProcessingState, Boolean>>()
    private var processJob: Job? = null
    private var watching = 0

    private fun TestScope.runs(process: suspend (File) -> ProcessingState) = ProcessingRuns(
        scope = backgroundScope,
        process = process,
        watch = {
            watching++
            try {
                awaitCancellation()
            } finally {
                watching--
            }
        },
        onFinished = { _, state, last -> finished += state to last },
    )

    /** Like ProcessingRepository.process: suspends until its own job is cancelled, then rethrows. */
    private val hangingProcess: suspend (File) -> ProcessingState = {
        processJob = currentCoroutineContext().job
        awaitCancellation()
    }

    @Test
    fun `a cancelled run cleans up, reports cancelled and can start again`() = runTest {
        val runs = runs(hangingProcess)
        assertTrue(runs.start(scan))
        runCurrent()
        assertEquals(1, watching)

        processJob!!.cancel() // what ProcessingRepository.cancel does on the Cancel action
        runCurrent()

        assertEquals(listOf(ProcessingState.Cancelled to true), finished)
        assertEquals(0, watching)
        assertTrue(runs.isEmpty)
        assertFalse(runs.isRunning(scan))
        assertTrue(runs.start(scan))
    }

    @Test
    fun `a finished run reports its result and the last flag`() = runTest {
        val done = ProcessingState.Done(File(scan, "splat.ply"))
        val runs = runs { done }
        runs.start(scan)
        runCurrent()
        assertEquals(listOf<Pair<ProcessingState, Boolean>>(done to true), finished)
        assertTrue(runs.isEmpty)
    }

    @Test
    fun `runs are keyed by normalized absolute path`() = runTest {
        val runs = runs(hangingProcess)
        assertTrue(runs.start(File("scans/./one")))
        runCurrent()
        assertTrue(runs.isRunning(scan.absoluteFile))
        assertFalse(runs.start(File("scans/x/../one")))
    }

    @Test
    fun `the last flag is false while another scan still runs`() = runTest {
        val runs = runs(hangingProcess)
        runs.start(File("scans/two"))
        runCurrent()
        val first = processJob!!
        runs.start(scan)
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(listOf(ProcessingState.Cancelled to false), finished)
        assertFalse(runs.isEmpty)
    }
}
