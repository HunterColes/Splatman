package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.splat.model.SplatCloud

/** What validating a picked file found: a cloud to save, or why it can't be opened. */
sealed interface ImportValidation {
    /** A Gaussian splat the viewer can draw; [name] is the file name without its extension. */
    class Valid(val name: String, val cloud: SplatCloud) : ImportValidation

    /** A PLY with positions (and maybe colours) but no scale, rotation or opacity. */
    data object PointCloud : ImportValidation

    /** Not a .ply or .splat splat file; [extension] is how the message names it (".obj", or the file name). */
    data class Unsupported(val extension: String) : ImportValidation

    /** The header reads, but the data is truncated, holds no splats or has values that are not numbers. */
    data object Corrupt : ImportValidation

    /** More splats than the device's budget. */
    data class TooLarge(val splatCount: Long) : ImportValidation

    /** Copying it needs [neededBytes] free; [availableBytes] are. */
    data class NoSpace(val neededBytes: Long, val availableBytes: Long) : ImportValidation
}
