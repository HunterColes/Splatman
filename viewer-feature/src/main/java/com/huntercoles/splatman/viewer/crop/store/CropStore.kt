package com.huntercoles.splatman.viewer.crop.store

import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.SplatSource

/**
 * Keeps a crop on disk (ADR 0011 rules 6 and 7): the crop UI's only store. Scans and samples only; a
 * [SplatSource.Document] is refused with [IllegalArgumentException]. File work runs on the IO dispatcher.
 */
interface CropStore {
    /** Makes [cloud] (already cropped, frame W) the source's splat, keeping the first original. */
    suspend fun apply(source: SplatSource, cloud: SplatCloud)

    /** Puts the original back and forgets the crop; a no-op when there is no original. */
    suspend fun revert(source: SplatSource)

    /** True while an original is kept, so Revert has something to restore. */
    suspend fun hasOriginal(source: SplatSource): Boolean
}

/** Told after a crop changed a scan's `splat.ply`; app binds it to `ScanRepository.refresh` (the Library count). */
fun interface ScanChangeListener {
    suspend fun changed(scanId: String)
}
