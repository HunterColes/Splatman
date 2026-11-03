package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.OutputStream
import com.huntercoles.splatman.core.preferences.ExportFormat as SettingsFormat

/** The export formats of ADR 0007. Only those with a codec in [ExportCodecs.AVAILABLE] are offered. */
enum class ExportFormat(val extension: String, val label: String) {
    PLY("ply", "PLY"),
    SPLAT("splat", ".splat"),
    SPZ("spz", "SPZ"),
    ;

    companion object {
        /** The format Settings > Default export format names. */
        fun of(setting: SettingsFormat): ExportFormat = when (setting) {
            SettingsFormat.Ply -> PLY
            SettingsFormat.Splat -> SPLAT
        }
    }
}

/**
 * Writes a cloud (frame W) in one format; [expectedBytes] is the exact output size for a cloud of `count` splats at
 * `shDegree`, known before the cloud is loaded (size estimates, the storage check, progress).
 */
class ExportCodec(
    val format: ExportFormat,
    val expectedBytes: (count: Int, shDegree: Int) -> Long,
    val write: (SplatCloud, OutputStream) -> Unit,
)

object ExportCodecs {
    /** Every file is sent as a plain binary stream: splat formats have no registered MIME type. */
    const val MIME = "application/octet-stream"

    private const val PLY_FLOATS = 17
    private const val FLOAT_BYTES = 4

    private val ply = ExportCodec(
        format = ExportFormat.PLY,
        expectedBytes = { count, degree ->
            val rest = (degree + 1) * (degree + 1) - 1
            PlyWriter.header(count, degree).length + count.toLong() * (PLY_FLOATS + 3 * rest) * FLOAT_BYTES
        },
        write = { c, out -> PlyWriter.write(c, out) },
    )

    private val splat = ExportCodec(
        format = ExportFormat.SPLAT,
        expectedBytes = { count, _ -> count.toLong() * SplatCodec.BYTES_PER_SPLAT },
        write = { c, out -> SplatCodec.write(c, out) },
    )

    /** PLY first (the default). SPZ joins this list when splat-io has an SPZ writer (#34). */
    val AVAILABLE: List<ExportCodec> = listOf(ply, splat)

    val DEFAULT: ExportFormat = ExportFormat.PLY

    fun of(format: ExportFormat): ExportCodec? = AVAILABLE.firstOrNull { it.format == format }
}

/** One scan in an export: its name and what its file size depends on. */
data class ExportEntry(val name: String, val count: Int, val shDegree: Int)

/** The size of an export in bytes: the sum of each scan's exact file size in [format]; 0 for a format with no codec. */
object ExportSize {
    fun of(format: ExportFormat, entries: List<ExportEntry>): Long {
        val codec = ExportCodecs.of(format) ?: return 0
        return entries.sumOf { codec.expectedBytes(it.count, it.shDegree) }
    }
}

/**
 * The low-storage check made before writing: an export of `needed` bytes needs that much free plus [HEADROOM], so a
 * write never fills the phone. An unknown free space (`free` null) is not low.
 */
object ExportStorage {
    const val HEADROOM: Long = 16L shl 20

    fun isLow(needed: Long, free: Long?): Boolean = free != null && free - needed < HEADROOM
}
