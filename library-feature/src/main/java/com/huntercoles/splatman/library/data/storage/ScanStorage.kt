package com.huntercoles.splatman.library.data.storage

import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import java.io.File

/**
 * What the scans take on disk. [usedBytes] is everything under the scans folder, [scanCount] its scan folders and
 * [clearableBytes] the capture frames that [ScanStorage.clearFrames] would free.
 */
data class StorageSummary(val usedBytes: Long, val scanCount: Int, val clearableBytes: Long)

/**
 * Counts and trims the scan folders under [root] (ADR 0007). Only the frames (`images/`) of a scan that is already
 * built (has its `splat.ply`) are clearable: the scan stays viewable, it just can't be rebuilt. Blocking; call it off
 * the main thread.
 */
class ScanStorage(private val root: File) {

    fun measure(): StorageSummary {
        val folders = scanFolders()
        return StorageSummary(
            usedBytes = folders.sumOf(::sizeOf),
            scanCount = folders.size,
            clearableBytes = folders.mapNotNull(::clearableFrames).sumOf(::sizeOf),
        )
    }

    /** Deletes the frames of every built scan; returns the bytes freed. */
    fun clearFrames(): Long = scanFolders().mapNotNull(::clearableFrames).sumOf { images ->
        val size = sizeOf(images)
        images.deleteRecursively()
        size - if (images.exists()) sizeOf(images) else 0L
    }

    private fun scanFolders(): List<File> = root.listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun clearableFrames(folder: File): File? {
        val images = File(folder, ScanRepositoryImpl.IMAGES_DIR)
        return images.takeIf { it.isDirectory && File(folder, ScanRepositoryImpl.SPLAT_FILE).isFile }
    }

    private fun sizeOf(file: File): Long = file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
