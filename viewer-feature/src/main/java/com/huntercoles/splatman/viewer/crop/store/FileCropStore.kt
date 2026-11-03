package com.huntercoles.splatman.viewer.crop.store

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * [CropStore] on the app's files (ADR 0011 rules 6 and 7). A scan's Apply writes `splat.ply.tmp`, keeps the first
 * original as `splat.uncropped.ply` (through its own tmp), then renames the tmp over `splat.ply`, so every crash
 * point leaves a valid `splat.ply`. A failure or cancel before the last rename deletes the tmps and keeps the old
 * file. A sample's crop is one file in `sample-crops/`, written through a tmp.
 */
class FileCropStore(
    private val filesDir: File,
    private val listener: ScanChangeListener,
    private val io: CoroutineDispatcher,
    private val ops: CropFileOps = DiskCropFileOps,
) : CropStore {

    override suspend fun apply(source: SplatSource, cloud: SplatCloud) {
        when (source) {
            is SplatSource.Scan -> {
                val folder = CropPaths.scanFolder(filesDir, source)
                withContext(io) { applyScan(folder, cloud) }
                notify(source)
            }
            is SplatSource.Sample -> {
                val target = CropPaths.sampleCrop(filesDir, source)
                withContext(io) { applySample(target, cloud) }
            }
            is SplatSource.Document -> refuse()
        }
    }

    override suspend fun revert(source: SplatSource) {
        when (source) {
            is SplatSource.Scan -> {
                val folder = CropPaths.scanFolder(filesDir, source)
                val reverted = withContext(io) {
                    deleteStaleTmps(folder)
                    val original = File(folder, CropPaths.UNCROPPED_FILE)
                    if (original.isFile) {
                        ops.rename(original, File(folder, SplatSource.Scan.SPLAT_FILE))
                        true
                    } else {
                        false
                    }
                }
                if (reverted) notify(source)
            }
            is SplatSource.Sample -> {
                val target = CropPaths.sampleCrop(filesDir, source)
                withContext(io) {
                    tmpOf(target).delete()
                    target.delete()
                }
            }
            is SplatSource.Document -> refuse()
        }
    }

    override suspend fun hasOriginal(source: SplatSource): Boolean = when (source) {
        is SplatSource.Scan -> {
            val folder = CropPaths.scanFolder(filesDir, source)
            withContext(io) { File(folder, CropPaths.UNCROPPED_FILE).isFile }
        }
        is SplatSource.Sample -> {
            val target = CropPaths.sampleCrop(filesDir, source)
            withContext(io) { target.isFile }
        }
        is SplatSource.Document -> refuse()
    }

    private suspend fun applyScan(folder: File, cloud: SplatCloud) {
        val current = File(folder, SplatSource.Scan.SPLAT_FILE)
        if (!current.isFile) throw FileNotFoundException("The scan has no splat to crop.")
        deleteStaleTmps(folder)
        val currentTmp = tmpOf(current)
        val original = File(folder, CropPaths.UNCROPPED_FILE)
        val originalTmp = tmpOf(original)
        var committed = false
        try {
            // 1. The cropped cloud, beside the current splat.
            ops.write(currentTmp, cloud)
            currentCoroutineContext().ensureActive()
            // 2. The first original, kept once.
            if (!original.exists()) {
                ops.copy(current, originalTmp)
                ops.rename(originalTmp, original)
            }
            currentCoroutineContext().ensureActive()
            // 3. The commit.
            ops.rename(currentTmp, current)
            committed = true
        } finally {
            if (!committed) {
                originalTmp.delete()
                currentTmp.delete()
            }
        }
    }

    private suspend fun applySample(target: File, cloud: SplatCloud) {
        val dir = target.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw FileNotFoundException("Could not make ${dir.name}.")
        val tmp = tmpOf(target)
        tmp.delete()
        var committed = false
        try {
            ops.write(tmp, cloud)
            currentCoroutineContext().ensureActive()
            ops.rename(tmp, target)
            committed = true
        } finally {
            if (!committed) tmp.delete()
        }
    }

    /** The Library recounts once the new splat is in place; a late cancel must not skip it. */
    private suspend fun notify(source: SplatSource.Scan) {
        withContext(NonCancellable) { listener.changed(source.scanId) }
    }

    private fun deleteStaleTmps(folder: File) {
        folder.listFiles { f -> f.isFile && f.name.endsWith(CropPaths.TMP_SUFFIX) }?.forEach { it.delete() }
    }

    private fun tmpOf(file: File) = File(file.parentFile, file.name + CropPaths.TMP_SUFFIX)

    private fun refuse(): Nothing =
        throw IllegalArgumentException("A picked file cannot be cropped; only scans and samples can.")
}
