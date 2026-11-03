package com.huntercoles.splatman.splat.io.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/** The tracking stream's luma frames, `w*h` bytes each, back to back (ADR 0016). */
const val STREAM_Y8_FILE: String = "stream.y8"

/** The tracking stream's per-frame index (ADR 0016). */
const val STREAM_CSV_FILE: String = "stream.csv"

/** The `stream.csv` header. */
const val STREAM_HEADER: String = "index,timestamp_ns,exposure_ns,rolling_shutter_skew_ns,focus_distance_dpt"

/** `stream` of `capture.json`: where the tracking stream lives and its frame size, pixels. */
@Serializable
data class TrackingStreamInfo(
    @SerialName("file_path") val filePath: String = STREAM_Y8_FILE,
    @SerialName("index_path") val indexPath: String = STREAM_CSV_FILE,
    val w: Int,
    val h: Int,
    /** How many sensor pixels per stream pixel along each axis. */
    val downsample: Int,
)

/** One `stream.csv` row; nulls are cells the phone did not report. */
data class StreamEntry(
    val index: Int,
    val timestampNs: Long,
    val exposureNs: Long?,
    val rollingShutterSkewNs: Long?,
    val focusDistanceDpt: Float?,
)

/**
 * Streams the tracking stream (ADR 0016) into [directory]: each [write] appends one `w*h` luma frame to `stream.y8`
 * and a row to `stream.csv`. Both files are truncated on open; nothing is kept in memory. Numbers use
 * [Long.toString]/[Float.toString], which are locale-independent (as `Locale.ROOT`). [close] flushes and fsyncs both.
 */
class TrackingStreamWriter(
    directory: File,
    val w: Int,
    val h: Int,
    val downsample: Int,
) : Closeable {
    private val y8Stream: FileOutputStream
    private val y8: BufferedOutputStream
    private val csvStream: FileOutputStream
    private val csv: BufferedWriter
    private var count = 0
    private var closed = false

    init {
        require(w > 0 && h > 0) { "size must be positive, got ${w}x$h" }
        require(downsample > 0) { "downsample must be positive, got $downsample" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.path}")
        y8Stream = FileOutputStream(File(directory, STREAM_Y8_FILE), false)
        y8 = BufferedOutputStream(y8Stream, BUFFER_BYTES)
        csvStream = FileOutputStream(File(directory, STREAM_CSV_FILE), false)
        csv = BufferedWriter(OutputStreamWriter(csvStream, Charsets.US_ASCII))
        csv.write(STREAM_HEADER)
        csv.write("\n")
    }

    /** The `stream` entry for `capture.json`. */
    val info: TrackingStreamInfo get() = TrackingStreamInfo(w = w, h = h, downsample = downsample)

    /** Frames written so far. */
    val frameCount: Int
        @Synchronized get() = count

    /** Appends one frame; [y] must be exactly `w*h` bytes, [focusDpt] finite when given. */
    @Synchronized
    fun write(timestampNs: Long, exposureNs: Long?, skewNs: Long?, focusDpt: Float?, y: ByteArray) {
        check(!closed) { "TrackingStreamWriter is closed" }
        require(y.size == w * h) { "frame must be ${w * h} bytes (${w}x$h), got ${y.size}" }
        require(focusDpt == null || focusDpt.isFinite()) { "focus distance must be finite, got $focusDpt" }
        y8.write(y)
        csv.write(count.toString())
        csv.write(",")
        csv.write(timestampNs.toString())
        csv.write(",")
        csv.write(exposureNs?.toString().orEmpty())
        csv.write(",")
        csv.write(skewNs?.toString().orEmpty())
        csv.write(",")
        csv.write(focusDpt?.toString().orEmpty())
        csv.write("\n")
        count++
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            y8.flush()
            y8Stream.fd.sync()
            csv.flush()
            csvStream.fd.sync()
        } finally {
            try {
                y8.close()
            } finally {
                csv.close()
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }
}

/**
 * Reads a tracking stream back: [entries] from `stream.csv`, and [frame] seeks into `stream.y8` for one frame, so
 * the luma file is never read whole. Construction checks the header, every row, and that `stream.y8` holds exactly
 * `rows * w * h` bytes, else throws [CaptureBundleException].
 */
class TrackingStreamReader @Throws(CaptureBundleException::class) constructor(
    directory: File,
    val info: TrackingStreamInfo,
) : Closeable {
    /** Every `stream.csv` row in order; row `i` has index `i`. */
    val entries: List<StreamEntry>
    private val frameBytes: Long
    private val file: RandomAccessFile

    init {
        if (info.w <= 0 || info.h <= 0) throw CaptureBundleException("stream: size must be positive")
        if (info.downsample <= 0) throw CaptureBundleException("stream: downsample must be positive")
        val csvFile = File(directory, info.indexPath)
        val y8File = File(directory, info.filePath)
        if (!csvFile.isFile) throw CaptureBundleException("stream: missing file ${info.indexPath}")
        if (!y8File.isFile) throw CaptureBundleException("stream: missing file ${info.filePath}")
        entries = readIndex(csvFile)
        frameBytes = info.w.toLong() * info.h
        val expected = entries.size * frameBytes
        val actual = y8File.length()
        if (actual != expected) {
            throw CaptureBundleException(
                "stream: ${info.filePath} is $actual bytes, expected $expected (${entries.size} x ${info.w}x${info.h})",
            )
        }
        file = RandomAccessFile(y8File, "r")
    }

    /** Number of frames. */
    val size: Int get() = entries.size

    /** Frame [i]'s `w*h` luma bytes, read with one seek. */
    @Synchronized
    @Throws(IOException::class)
    fun frame(i: Int): ByteArray {
        if (i !in entries.indices) throw IndexOutOfBoundsException("frame $i of ${entries.size}")
        val out = ByteArray(frameBytes.toInt())
        file.seek(i * frameBytes)
        try {
            file.readFully(out)
        } catch (e: EOFException) {
            throw IOException("${info.filePath}: truncated at frame $i", e)
        }
        return out
    }

    override fun close() = file.close()

    private fun readIndex(csvFile: File): List<StreamEntry> {
        val name = info.indexPath
        val rows = ArrayList<StreamEntry>()
        csvFile.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEachIndexed { i, line ->
                if (i == 0) {
                    if (line != STREAM_HEADER) throw CaptureBundleException("$name:1: header must be $STREAM_HEADER")
                } else if (line.isNotEmpty()) {
                    val e = parse(line)
                    if (e == null || e.index != rows.size) {
                        throw CaptureBundleException("$name:${i + 1}: bad row \"$line\"")
                    }
                    rows += e
                }
            }
        }
        return rows
    }

    private fun parse(line: String): StreamEntry? {
        val c = line.split(',')
        if (c.size != 5) return null
        val index = c[0].toIntOrNull() ?: return null
        val t = c[1].toLongOrNull() ?: return null
        val exposure = optional(c[2]) { it.toLongOrNull() } ?: return null
        val skew = optional(c[3]) { it.toLongOrNull() } ?: return null
        val focus = optional(c[4]) { s -> s.toFloatOrNull()?.takeIf { it.isFinite() } } ?: return null
        return StreamEntry(index, t, exposure.value, skew.value, focus.value)
    }

    /** An empty cell is a present null; an unparsable one is no cell at all (bad row). */
    private class Cell<T>(val value: T?)

    private inline fun <T> optional(cell: String, parse: (String) -> T?): Cell<T>? =
        if (cell.isEmpty()) Cell(null) else parse(cell)?.let { Cell(it) }
}
