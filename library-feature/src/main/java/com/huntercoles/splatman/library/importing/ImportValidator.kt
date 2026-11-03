package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.library.data.format.SplatFileHeader
import com.huntercoles.splatman.splat.io.FormatDetector
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.io.splat.SplatCodec
import com.huntercoles.splatman.splat.model.SplatCloud
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * Tells whether a picked file is a Gaussian splat Splatman can open (docs/design/screens/import.md, States), by
 * content, not extension (ADR 0007, [FormatDetector]): a PLY must carry the 3DGS properties (positions alone is a
 * point cloud), a `.splat` must be whole 32-byte records. Either is decoded in full, so a truncated body or a value
 * that is not a number is caught here and not in the viewer. [maxSplats] is the device budget; [heapBudget] is the
 * memory free for a decode, so a file the heap cannot hold is refused as too large instead of crashing.
 * Pure Kotlin; blocking; every stream [validate]'s `open` returns is closed.
 */
class ImportValidator(
    private val maxSplats: Long = MAX_SPLATS,
    private val shCapBytes: Long = PlyReader.SH_CAP_BYTES,
    private val heapBudget: () -> Long = ::freeHeapBytes,
) {

    /** [open] gives a fresh stream of the file each call (it is read up to three times). */
    fun validate(fileName: String, open: () -> InputStream): ImportValidation {
        val head = open().use { readUpTo(it, HEADER_BYTES) }
        val format = FormatDetector.detect(head, head.size.toLong())
        return when {
            format == SplatFormat.PLY -> validatePly(fileName, open)
            format == SplatFormat.SPZ || head.isEmpty() -> unsupported(fileName)
            else -> validateSplat(fileName, head, open)
        }
    }

    private fun validatePly(fileName: String, open: () -> InputStream): ImportValidation {
        val header = try {
            open().use { SplatFileHeader.readPly(it.buffered()) }
        } catch (_: IOException) {
            return unsupported(fileName)
        }
        val props = header.properties.toSet()
        if (!props.containsAll(POSITION)) return unsupported(fileName)
        if (!props.containsAll(SPLAT_ONLY)) return ImportValidation.PointCloud
        if (header.splatCount > maxSplats) return ImportValidation.TooLarge(header.splatCount.toLong())
        if (header.splatCount <= 0) return ImportValidation.Corrupt
        val cloud = try {
            open().use { PlyReader.readWithReport(it, shCapBytes).cloud }
        } catch (_: IOException) {
            return ImportValidation.Corrupt
        } catch (_: OutOfMemoryError) {
            return ImportValidation.TooLarge(header.splatCount.toLong())
        }
        return valid(fileName, cloud)
    }

    /**
     * Anything not PLY or SPZ: whole 32-byte records that decode to a usable cloud are a `.splat`. Raw bytes of such a
     * size pass the detector by chance, so a failure is "damaged" only when the name says `.splat`, else unsupported.
     */
    private fun validateSplat(fileName: String, head: ByteArray, open: () -> InputStream): ImportValidation {
        val namedSplat = extensionOf(fileName) == SPLAT_EXTENSION
        val failed = if (namedSplat) ImportValidation.Corrupt else unsupported(fileName)
        val total = open().use(::countBytes)
        if (FormatDetector.detect(head, total) != SplatFormat.SPLAT) return failed
        val count = total / SPLAT_RECORD_BYTES
        val tooLarge = if (namedSplat) ImportValidation.TooLarge(count) else failed
        if (count > maxSplats || count * SPLAT_DECODE_BYTES_PER_SPLAT > heapBudget()) return tooLarge
        val cloud = try {
            val bytes = ByteArray(total.toInt())
            val got = open().use { readUpTo(it, bytes) }
            if (got != bytes.size) return failed
            SplatCodec.read(bytes)
        } catch (_: OutOfMemoryError) {
            return tooLarge
        }
        val result = valid(fileName, cloud)
        return if (result is ImportValidation.Valid) result else failed
    }

    private fun valid(fileName: String, cloud: SplatCloud): ImportValidation =
        if (isUsable(cloud)) ImportValidation.Valid(nameOf(fileName), cloud) else ImportValidation.Corrupt

    private fun unsupported(fileName: String) =
        ImportValidation.Unsupported(extensionOf(fileName)?.let { ".$it" } ?: fileName)

    companion object {
        /** The device budget until the renderer sets one per GPU: 8M splats, the viewer's `.splat` read cap. */
        const val MAX_SPLATS: Long = 8_000_000

        private const val HEADER_BYTES = 512
        private const val CHUNK_BYTES = 1 shl 16
        private const val SPLAT_RECORD_BYTES = 32L

        /**
         * Peak heap of a `.splat` decode per splat: the 32-byte record, the decoded cloud (14 floats) and the copy
         * `FrameConversions.fileToWorld` makes of it.
         */
        private const val SPLAT_DECODE_BYTES_PER_SPLAT = SPLAT_RECORD_BYTES + 2 * 14 * Float.SIZE_BYTES

        /** Heap the runtime can still hand out: its cap less what is in use. */
        fun freeHeapBytes(): Long = Runtime.getRuntime().run { maxMemory() - (totalMemory() - freeMemory()) }
        private const val SPLAT_EXTENSION = "splat"
        private const val FALLBACK_NAME = "Imported splat"

        /** As viewer-feature `SplatSanity`: a splat centre or size past 100 km is broken data. */
        private const val MAX_COORD = 1e5f
        private val MAX_LOG_SCALE = ln(MAX_COORD)

        private val POSITION = setOf("x", "y", "z")
        private val SPLAT_ONLY = setOf("opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3")

        /** `Desk plant.ply` -> "Desk plant"; a name that is only an extension is kept whole. */
        fun nameOf(fileName: String): String =
            fileName.substringBeforeLast('.').trim().ifEmpty { fileName.trim() }.ifEmpty { FALLBACK_NAME }

        private fun extensionOf(fileName: String): String? =
            if ('.' in fileName) fileName.substringAfterLast('.').lowercase().takeIf { it.isNotBlank() } else null

        /** Has splats, every value finite, every centre and size inside [MAX_COORD]. */
        fun isUsable(cloud: SplatCloud): Boolean {
            if (cloud.count <= 0) return false
            for (i in 0 until 3 * cloud.count) {
                val p = cloud.positions[i]
                val s = cloud.logScales[i]
                if (!p.isFinite() || abs(p) > MAX_COORD) return false
                if (!s.isFinite() || s > MAX_LOG_SCALE) return false
                if (!cloud.shDc[i].isFinite()) return false
            }
            return cloud.rotations.all { it.isFinite() } &&
                cloud.opacityLogits.all { it.isFinite() } &&
                cloud.shRest.all { it.isFinite() }
        }

        private fun readUpTo(input: InputStream, max: Int): ByteArray {
            val buf = ByteArray(max)
            return buf.copyOf(readUpTo(input, buf))
        }

        /** Fills [buf] from [input] until it is full or the stream ends; the count read. */
        private fun readUpTo(input: InputStream, buf: ByteArray): Int {
            var got = 0
            while (got < buf.size) {
                val r = input.read(buf, got, buf.size - got)
                if (r < 0) break
                got += r
            }
            return got
        }

        private fun countBytes(input: InputStream): Long {
            val chunk = ByteArray(CHUNK_BYTES)
            var total = 0L
            while (true) {
                val r = input.read(chunk)
                if (r < 0) return total
                total += r
            }
        }
    }
}
