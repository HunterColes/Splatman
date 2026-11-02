package com.huntercoles.splatman.capture.processing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * The service's book of runs, one per scan, keyed like [ProcessingRepository] (normalized absolute path). Each run
 * calls [process] with [watch] beside it; however the run ends (result, failure or cancel), the watcher stops, the
 * run is removed and [onFinished] gets its final state and whether it was the last run.
 */
class ProcessingRuns(
    private val scope: CoroutineScope,
    private val process: suspend (File) -> ProcessingState,
    private val watch: suspend (File) -> Unit,
    private val onFinished: (scan: File, state: ProcessingState, last: Boolean) -> Unit,
) {
    private val runs = mutableMapOf<String, Job>()

    val isEmpty: Boolean get() = runs.isEmpty()

    fun isRunning(scan: File): Boolean = runs.containsKey(key(scan))

    /** Starts a run for [scan]; returns false (and does nothing) when one is already going. */
    fun start(scan: File): Boolean {
        val key = key(scan)
        if (runs.containsKey(key)) return false
        val watcher = scope.launch { watch(scan) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var result: ProcessingState = ProcessingState.Cancelled
            try {
                result = process(scan)
            } catch (e: CancellationException) {
                result = ProcessingState.Cancelled
                throw e
            } finally {
                watcher.cancel()
                runs.remove(key)
                onFinished(scan, result, runs.isEmpty())
            }
        }
        runs[key] = job
        job.start()
        return true
    }

    private fun key(scan: File): String = scan.absoluteFile.normalize().path
}
