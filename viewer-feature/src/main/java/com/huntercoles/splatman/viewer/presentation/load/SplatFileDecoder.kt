package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.io.FormatDetector
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A file the viewer cannot open; [message] is shown to the user as is. */
class SplatFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A decoded file: the cloud in frame W, its detected [format], and whether the SH cap dropped its rest terms. */
class DecodedSplats(val cloud: SplatCloud, val format: SplatFormat, val shDropped: Boolean)

/**
 * Opens a splat file by content (ADR 0007, [FormatDetector]): a PLY is streamed through [PlyReader] with the ADR 0002
 * SH memory cap [shCapBytes], so a 1M-splat SH3 file keeps degree 0 instead of running out of memory; anything else
 * is read whole (at most [maxRawBytes]) and decoded as `.splat` when its size fits. SPZ is recognised but not read yet.
 * Pure Kotlin; blocking; the stream is not closed.
 */
class SplatFileDecoder(
    private val shCapBytes: Long = PlyReader.SH_CAP_BYTES,
    private val maxRawBytes: Long = MAX_RAW_BYTES,
) {
    fun decode(input: InputStream): DecodedSplats {
        val stream = BufferedInputStream(input, BUFFER_BYTES)
        stream.mark(HEADER_BYTES)
        val header = ByteArray(HEADER_BYTES)
        val got = readUpTo(stream, header)
        stream.reset()
        val head = header.copyOf(got)
        if (got == 0) throw SplatFileException("The file is empty.")

        if (FormatDetector.detect(head, got.toLong()) == SplatFormat.PLY) {
            val result = try {
                PlyReader.readWithReport(stream, shCapBytes)
            } catch (e: IOException) {
                throw SplatFileException("Not a readable 3DGS PLY: ${e.message}", e)
            }
            return DecodedSplats(result.cloud, SplatFormat.PLY, result.report.shDropped)
        }

        val bytes = readAll(stream)
        return when (FormatDetector.detect(head, bytes.size.toLong())) {
            SplatFormat.SPLAT -> DecodedSplats(SplatCodec.read(bytes), SplatFormat.SPLAT, shDropped = false)
            SplatFormat.SPZ -> throw SplatFileException("SPZ files cannot be opened yet.")
            else -> throw SplatFileException("Not a splat file (PLY or .splat).")
        }
    }

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val r = stream.read(chunk)
            if (r < 0) break
            total += r
            if (total > maxRawBytes) {
                throw SplatFileException("The file is too large to open (over ${maxRawBytes shr 20} MB).")
            }
            out.write(chunk, 0, r)
        }
        return out.toByteArray()
    }

    private fun readUpTo(stream: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val r = stream.read(buf, got, buf.size - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    companion object {
        /** 8M `.splat` splats; a PLY is streamed and not bound by this. */
        const val MAX_RAW_BYTES: Long = 256L * 1024 * 1024
        private const val HEADER_BYTES = 512
        private const val BUFFER_BYTES = 1 shl 16
    }
}
