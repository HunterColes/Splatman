package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.model.SeedPoints
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.packet.TrainingInput
import com.huntercoles.splatman.splat.packet.TrainingProgress
import com.huntercoles.splatman.splat.train.Trainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the bound [Trainer] on a saved capture bundle and writes the scan's `splat.ply` (ADR 0005 runner).
 *
 * A scan is its bundle folder. The splat is written to `splat.ply.tmp`, synced, then renamed, so a reader never sees
 * a half file; a `processing.running` marker lives in the folder for the length of a run, so a run the process died in
 * reads back as [ProcessingState.Failed] and can simply be started again. The bundle itself is only read.
 */
@Singleton
class ProcessingRepository @Inject constructor(
    private val trainer: Trainer,
    private val decoder: KeyframeDecoder,
    private val thermal: ThermalMonitor,
    private val config: TrainingConfig,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<ProcessingState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** The scan's state; before any run in this process it is read from the folder. */
    fun state(scan: File): StateFlow<ProcessingState> = stateFlow(scan).asStateFlow()

    /** The trainer's progress for [scan] while it runs or is paused. */
    fun progress(scan: File): Flow<TrainingProgress> = stateFlow(scan).mapNotNull {
        when (it) {
            is ProcessingState.Running -> it.progress
            is ProcessingState.Paused -> it.progress
            else -> null
        }
    }.distinctUntilChanged()

    /** Whether a run for [scan] is in progress in this process. */
    fun isRunning(scan: File): Boolean = jobs.containsKey(key(scan))

    /**
     * Cancels [scan]'s run, if one is going. The bundle and any earlier `splat.ply` are left as they were; with no
     * earlier `splat.ply`, the run's latest snapshot (the seed splat at least) is written as the scan's result.
     */
    fun cancel(scan: File) {
        jobs[key(scan)]?.cancel()
    }

    /**
     * Processes [scan] in the calling coroutine and returns its final state; cancelling the caller (or [cancel])
     * stops it. A second call while one is running returns the current state without starting another.
     */
    suspend fun process(scan: File): ProcessingState {
        val key = key(scan)
        val state = stateFlow(scan)
        val job = currentCoroutineContext().job
        if (jobs.putIfAbsent(key, job) != null) return state.value
        val marker = File(scan, RUNNING_MARKER)
        val output = File(scan, SPLAT_FILE)
        val temp = File(scan, TEMP_FILE)
        state.value = ProcessingState.Running(null)
        var latestCloud: SplatCloud? = null
        try {
            withContext(dispatcher) {
                temp.delete()
                marker.writeText("")
                var last: TrainingProgress? = null
                train(scan, state) { progress ->
                    last = progress
                    progress.snapshot?.let { latestCloud = it }
                }
                val cloud = last?.snapshot ?: throw IOException("the trainer produced no splat")
                writeSplat(cloud, temp, output) { ensureActive() }
                marker.delete()
            }
            state.value = ProcessingState.Done(output)
        } catch (e: CancellationException) {
            val fallback = latestCloud
            try {
                if (fallback != null && !output.exists()) {
                    // The seed (or a later snapshot) stays the scan's result; an earlier splat.ply is never replaced.
                    // withContext rethrows the outer cancellation on return, hence the finally.
                    withContext(NonCancellable + dispatcher) {
                        runCatching { writeSplat(fallback, temp, output) }
                    }
                }
            } finally {
                cleanUp(temp, marker)
                state.value = ProcessingState.Cancelled
            }
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            cleanUp(temp, marker)
            state.value = ProcessingState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            jobs.remove(key, job)
        }
        return state.value
    }

    private inline fun writeSplat(cloud: SplatCloud, temp: File, output: File, beforeRename: () -> Unit = {}) {
        FileOutputStream(temp).use { file ->
            val out = BufferedOutputStream(file)
            PlyWriter.write(cloud, out)
            out.flush()
            file.fd.sync()
        }
        beforeRename()
        if (!temp.renameTo(output)) throw IOException("cannot rename $TEMP_FILE to $SPLAT_FILE")
    }

    private suspend fun train(
        scan: File,
        state: MutableStateFlow<ProcessingState>,
        onProgress: (TrainingProgress) -> Unit,
    ) {
        val bundle = CaptureBundleReader.read(scan)
        val keyframes = bundle.frames.mapIndexed { i, frame ->
            currentCoroutineContext().ensureActive()
            loadKeyframe(i, frame, decoder, config.imageLongSide)
        }
        val input = TrainingInput(keyframes, bundle.seed ?: SeedPoints(0, FloatArray(0), ByteArray(0)), config)
        trainer.train(input).collect { progress ->
            onProgress(progress)
            // The trainer runs in this collector, so suspending here holds it until the phone cools.
            if (thermal.severe.value) {
                state.value = ProcessingState.Paused(progress)
                thermal.severe.first { !it }
            }
            state.value = ProcessingState.Running(progress)
        }
    }

    private fun cleanUp(temp: File, marker: File) {
        temp.delete()
        marker.delete()
    }

    private fun stateFlow(scan: File): MutableStateFlow<ProcessingState> =
        states.getOrPut(key(scan)) { MutableStateFlow(stateOnDisk(scan)) }

    private fun stateOnDisk(scan: File): ProcessingState {
        val output = File(scan, SPLAT_FILE)
        return when {
            File(scan, RUNNING_MARKER).exists() -> ProcessingState.Failed(INTERRUPTED)
            output.isFile -> ProcessingState.Done(output)
            else -> ProcessingState.Idle
        }
    }

    private fun key(scan: File): String = scan.absoluteFile.normalize().path

    companion object {
        /** The trained splat, a 3DGS PLY (ADR 0007), in the scan's folder. */
        const val SPLAT_FILE = "splat.ply"
        const val TEMP_FILE = "splat.ply.tmp"
        const val RUNNING_MARKER = "processing.running"
        const val INTERRUPTED = "processing was interrupted; start it again"
    }
}
