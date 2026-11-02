package com.huntercoles.splatman.splat.io.ply

import com.huntercoles.splatman.splat.model.SplatCloud

/**
 * What [PlyReader] did with a file beyond decoding it. [fileShDegree] is the degree the file holds, [shDegree] the
 * degree of the returned cloud: lower when the SH memory cap of ADR 0002 dropped the rest coefficients.
 * [ignoredProperties] are vertex properties the reader skipped (normals not counted: the layout carries them).
 */
data class ReadReport(
    val count: Int,
    val fileShDegree: Int,
    val shDegree: Int,
    val ignoredProperties: List<String>,
) {
    /** True when the SH memory cap dropped the file's rest coefficients. */
    val shDropped: Boolean get() = shDegree < fileShDegree
}

/** A decoded cloud (frame W) and its [ReadReport]. */
class PlyReadResult(val cloud: SplatCloud, val report: ReadReport)
