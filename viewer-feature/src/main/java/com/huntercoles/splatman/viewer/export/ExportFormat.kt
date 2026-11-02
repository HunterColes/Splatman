package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.OutputStream

/** The export formats of ADR 0007. Only those with a codec in [ExportCodecs.AVAILABLE] are offered. */
enum class ExportFormat(val extension: String, val label: String) {
    PLY("ply", "PLY"),
    SPLAT("splat", ".splat"),
    SPZ("spz", "SPZ"),
}

/** Writes a cloud (frame W) in one format; [expectedBytes] is the output size, for progress. */
class ExportCodec(
    val format: ExportFormat,
    val expectedBytes: (SplatCloud) -> Long,
    val write: (SplatCloud, OutputStream) -> Unit,
)

object ExportCodecs {
    /** Every file is sent as a plain binary stream: splat formats have no registered MIME type. */
    const val MIME = "application/octet-stream"

    private val ply = ExportCodec(
        format = ExportFormat.PLY,
        expectedBytes = { c ->
            PlyWriter.header(c.count, c.shDegree).length + c.count.toLong() * (17 + 3 * c.shRestPerChannel) * 4
        },
        write = { c, out -> PlyWriter.write(c, out) },
    )

    private val splat = ExportCodec(
        format = ExportFormat.SPLAT,
        expectedBytes = { c -> c.count.toLong() * SplatCodec.BYTES_PER_SPLAT },
        write = { c, out -> SplatCodec.write(c, out) },
    )

    /** PLY first (the default). SPZ joins this list when splat-io has an SPZ writer (#34). */
    val AVAILABLE: List<ExportCodec> = listOf(ply, splat)

    val DEFAULT: ExportFormat = ExportFormat.PLY
}
