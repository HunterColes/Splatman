package com.huntercoles.splatman.library.domain.model

/** Where a scan is in its life: recorded, being built, built, or the build failed. */
enum class ScanStatus { CAPTURED, PROCESSING, READY, FAILED }

/**
 * One scan: a folder `scans/<folder>/` in the capture bundle layout of ADR 0007 and its row in the library.
 * [splatCount] is the size of `splat.ply` once READY (0 before), [keyframeCount] the number of recorded images.
 * [imported] scans came from a file (the import flow): a folder with only `splat.ply`, nothing to rebuild.
 * [sizeBytes] is what the scan folder takes on disk and [thumbnail] its `thumbnail.png` (#175), both read from the
 * folder when the list is read; null when the picture is missing or empty.
 */
data class Scan(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val status: ScanStatus,
    val splatCount: Int,
    val keyframeCount: Int,
    val folder: String,
    val imported: Boolean = false,
    val sizeBytes: Long = 0,
    val thumbnail: ScanThumbnail? = null,
)

/** A scan's `thumbnail.png`: its [path] and [stamp] (last modified), so a rewritten picture is decoded again. */
data class ScanThumbnail(val path: String, val stamp: Long)

/** Where tapping a library card goes. */
sealed interface OpenTarget {
    val scanId: String

    data class Viewer(override val scanId: String) : OpenTarget

    data class Processing(override val scanId: String) : OpenTarget
}

/** READY scans open in the Viewer; anything still to build (or failed) opens Processing. */
fun Scan.openTarget(): OpenTarget =
    if (status == ScanStatus.READY) OpenTarget.Viewer(id) else OpenTarget.Processing(id)

/** A splat file bundled with the app (`assets/samples`). Its [id] is the Viewer's scan id: `sample:<file>`. */
data class Sample(val id: String, val name: String, val fileName: String) {
    companion object {
        const val ID_PREFIX = "sample:"

        /** `measuring-tape.ply` -> "Measuring tape". */
        fun fromFileName(fileName: String): Sample {
            val stem = fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()
            return Sample(ID_PREFIX + fileName, stem.replaceFirstChar { it.uppercaseChar() }, fileName)
        }
    }
}
