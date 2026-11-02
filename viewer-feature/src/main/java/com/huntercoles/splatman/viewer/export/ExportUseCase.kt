package com.huntercoles.splatman.viewer.export

import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.splat.model.SplatCloud
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

sealed interface ExportResult {
    data object Done : ExportResult
    data class Failed(val message: String) : ExportResult
}

/**
 * Writes a cloud in an [ExportFormat] to the stream [open] gives, on [io]: the bytes are exactly the format's codec
 * output, `onProgress` gets 0..1 as they go, and a stream that cannot be opened or written ends in
 * [ExportResult.Failed] instead of an exception. The stream is closed when done.
 */
class ExportUseCase @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val codecs = ExportCodecs.AVAILABLE

    val formats: List<ExportFormat> = codecs.map { it.format }

    suspend fun export(
        cloud: SplatCloud,
        format: ExportFormat,
        open: () -> OutputStream,
        onProgress: (Float) -> Unit = {},
    ): ExportResult = withContext(io) {
        val codec = codecs.firstOrNull { it.format == format }
            ?: return@withContext ExportResult.Failed("${format.label} export is not available yet.")
        try {
            open().use { raw ->
                val out = ProgressStream(BufferedOutputStream(raw, BUFFER), codec.expectedBytes(cloud), onProgress)
                codec.write(cloud, out)
                out.flush()
            }
            onProgress(1f)
            ExportResult.Done
        } catch (e: IOException) {
            Timber.w(e, "Export as %s failed", format)
            ExportResult.Failed(WRITE_ERROR)
        } catch (e: SecurityException) {
            Timber.w(e, "Export as %s refused", format)
            ExportResult.Failed(DENIED_ERROR)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "Out of memory exporting %s", format)
            ExportResult.Failed(MEMORY_ERROR)
        }
    }

    /** Counts bytes and reports each whole percent once. */
    private class ProgressStream(
        out: OutputStream,
        private val expected: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterOutputStream(out) {
        private var written = 0L
        private var lastPercent = -1

        override fun write(b: Int) {
            out.write(b)
            advance(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            advance(len)
        }

        private fun advance(n: Int) {
            written += n
            if (expected <= 0) return
            val percent = (written * PERCENT / expected).toInt().coerceIn(0, PERCENT)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent / PERCENT.toFloat())
            }
        }
    }

    companion object {
        const val WRITE_ERROR = "The file could not be written."
        const val DENIED_ERROR = "Splatman may not write there."
        const val MEMORY_ERROR = "Not enough memory to export this splat."
        private const val PERCENT = 100
        private const val BUFFER = 1 shl 16
    }
}
