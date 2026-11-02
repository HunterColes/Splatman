package com.huntercoles.splatman.splat.io

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** A splat file's format, detected from its bytes (ADR 0007, import rules). */
enum class SplatFormat { PLY, SPZ, SPLAT, UNKNOWN }

/** Detects by content, not extension: `ply\n` magic, `NGSP` after gunzip, else `.splat` when size % 32 == 0. */
object FormatDetector {
    private val PLY_MAGIC = "ply\n".toByteArray(Charsets.US_ASCII)
    private val SPZ_MAGIC = "NGSP".toByteArray(Charsets.US_ASCII)

    /** [header] is the start of the file (a few hundred bytes is plenty), [size] the whole file's size in bytes. */
    fun detect(header: ByteArray, size: Long): SplatFormat = when {
        startsWith(header, PLY_MAGIC) -> SplatFormat.PLY
        isGzip(header) ->
            if (startsWith(gunzipPrefix(header), SPZ_MAGIC)) SplatFormat.SPZ else SplatFormat.UNKNOWN
        size > 0 && size % 32 == 0L -> SplatFormat.SPLAT
        else -> SplatFormat.UNKNOWN
    }

    private fun isGzip(b: ByteArray) = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun gunzipPrefix(b: ByteArray): ByteArray {
        val out = ByteArray(SPZ_MAGIC.size)
        var got = 0
        try {
            GZIPInputStream(ByteArrayInputStream(b)).use { z ->
                while (got < out.size) {
                    val r = z.read(out, got, out.size - got)
                    if (r < 0) break
                    got += r
                }
            }
        } catch (_: IOException) {
            // a truncated header ends the stream early; whatever was inflated is checked
        }
        return out.copyOf(got)
    }

    private fun startsWith(b: ByteArray, magic: ByteArray) =
        b.size >= magic.size && magic.indices.all { b[it] == magic[it] }
}
