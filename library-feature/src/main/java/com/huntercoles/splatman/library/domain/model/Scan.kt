package com.huntercoles.splatman.library.domain.model

/** Where a scan is in its life: recorded, being built, built, or the build failed. */
enum class ScanStatus { CAPTURED, PROCESSING, READY, FAILED }

/**
 * One scan: a folder `scans/<folder>/` in the capture bundle layout of ADR 0007 and its row in the library.
 * [splatCount] is the size of `splat.ply` once READY (0 before), [keyframeCount] the number of recorded images.
 */
data class Scan(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val status: ScanStatus,
    val splatCount: Int,
    val keyframeCount: Int,
    val folder: String,
)

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
