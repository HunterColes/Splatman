package com.huntercoles.splatman.viewer.crop.store

import com.huntercoles.splatman.viewer.presentation.load.SplatSamples
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import java.io.File

/** Where crops live under `filesDir` (ADR 0011). Every path is checked to stay inside its folder. */
object CropPaths {
    /** The scan folders (library-feature and capture-feature `SCANS_DIR`). */
    const val SCANS_DIR = "scans"

    /** A scan's first original, kept by its first Apply. */
    const val UNCROPPED_FILE = "splat.uncropped.ply"

    /** Cropped samples, one file per asset file name. */
    const val SAMPLE_CROPS_DIR = "sample-crops"

    const val TMP_SUFFIX = ".tmp"

    /** The scan's folder, or [IllegalArgumentException] when its id could leave `scans/`. */
    fun scanFolder(filesDir: File, source: SplatSource.Scan): File {
        val id = SplatSource.Scan.fromScanId(source.scanId)?.scanId
        require(id != null) { "Not a scan id: ${source.scanId}" }
        val root = File(filesDir, SCANS_DIR)
        return File(root, id).also { requireInside(root, it) }
    }

    /** The cropped copy of a sample, or [IllegalArgumentException] when its file name could leave the folder. */
    fun sampleCrop(filesDir: File, source: SplatSource.Sample): File {
        val name = source.assetPath.substringAfterLast('/')
        require(SplatSamples.fromScanId(SplatSamples.SCAN_ID_PREFIX + name) != null && !name.contains('\\')) {
            "Not a sample file: ${source.assetPath}"
        }
        val root = File(filesDir, SAMPLE_CROPS_DIR)
        return File(root, name).also { requireInside(root, it) }
    }

    private fun requireInside(root: File, file: File) {
        require(file.canonicalFile.parentFile == root.canonicalFile) { "Path leaves its folder: ${file.name}" }
    }
}
