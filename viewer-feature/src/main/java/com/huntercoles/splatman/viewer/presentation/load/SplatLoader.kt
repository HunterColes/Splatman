package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject

/** Where a splat file comes from: a bundled sample in the app's assets, or a document the user picked. */
sealed interface SplatSource {
    val name: String

    data class Sample(val assetPath: String, override val name: String) : SplatSource

    /** [uri] is the `content://` URI from the system picker, as a string. */
    data class Document(val uri: String, override val name: String = "File") : SplatSource

    /** A scan made on this phone: `filesDir/scans/<[scanId]>/splat.ply` (ADR 0007). */
    data class Scan(val scanId: String, override val name: String = scanId) : SplatSource {
        companion object {
            /** The scan's trained splat inside its folder (capture-feature `ProcessingRepository.SPLAT_FILE`). */
            const val SPLAT_FILE = "splat.ply"

            /** The scan a viewer route's `scanId` names, or null for a sample, a path or a blank id. */
            fun fromScanId(scanId: String?): Scan? {
                if (scanId.isNullOrBlank() || scanId.trim() != scanId || scanId.startsWith(".")) return null
                if (scanId.any { it == '/' || it == '\\' || it == ':' }) return null
                return Scan(scanId)
            }
        }
    }
}

/** The samples bundled by #5 (app/src/main/assets/samples/ASSETS-LICENSES.md), smallest first. */
object SplatSamples {
    val ALL: List<SplatSource.Sample> = listOf(
        SplatSource.Sample("samples/measuring-tape.ply", "Measuring tape"),
        SplatSource.Sample("samples/cactus-sh1.ply", "Cactus"),
    )

    /** The Library's id for a bundled sample (`sample:<file>`, library-feature `Sample.ID_PREFIX`). */
    const val SCAN_ID_PREFIX = "sample:"
    private const val DIR = "samples/"

    /** The sample a viewer route's `scanId` names, or null when it names a scan or nothing usable. */
    fun fromScanId(scanId: String?): SplatSource.Sample? {
        if (scanId == null || !scanId.startsWith(SCAN_ID_PREFIX)) return null
        val file = scanId.removePrefix(SCAN_ID_PREFIX)
        if (file.isBlank() || file.contains('/') || file.startsWith(".")) return null
        ALL.firstOrNull { it.assetPath == DIR + file }?.let { return it }
        val stem = file.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()
        return SplatSource.Sample(DIR + file, stem.replaceFirstChar { it.uppercaseChar() })
    }
}

/** Opens a [SplatSource]'s bytes. Blocking; the caller closes the stream. */
interface SplatOpener {
    fun open(source: SplatSource): InputStream

    /** The name to show for [source] (a picked document's display name). */
    fun displayName(source: SplatSource): String = source.name
}

/**
 * A loaded file, ready for the renderer: the decoded cloud, its packed GPU form and the starting camera.
 * [sizeBytes] is the file's length as read (a sample's cropped copy after a crop), for the details sheet.
 */
class LoadedScene(
    val name: String,
    val format: SplatFormat,
    val cloud: SplatCloud,
    val render: RenderScene,
    val framing: SceneFraming,
    val shDropped: Boolean,
    val sizeBytes: Long? = null,
) {
    val count: Int get() = cloud.count
}

/** Loads a [SplatSource] into a [LoadedScene] off the main thread; cancellable. */
interface SplatLoader {
    suspend fun load(source: SplatSource): LoadedScene
}

/** Reads and decodes on [io] (interruptible, so a cancel stops a long read), packs and frames on [cpu]. */
class DefaultSplatLoader @Inject constructor(
    private val opener: SplatOpener,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : SplatLoader {
    private val decoder = SplatFileDecoder()

    override suspend fun load(source: SplatSource): LoadedScene {
        val (name, decoded, size) = runInterruptible(io) {
            val name = opener.displayName(source)
            CountingInputStream(opener.open(source)).use {
                val decoded = decoder.decode(it)
                Triple(name, decoded, it.drain())
            }
        }
        return withContext(cpu) {
            val cloud = decoded.cloud
            // A fresh packer per load: the renderer may still re-upload the previous scene's buffer.
            val packed = SplatPacker().pack(cloud)
            ensureActive()
            val framing = SceneFraming.of(cloud.positions, cloud.count, ViewerConstants.FOV_DEGREES)
            LoadedScene(
                name = name,
                format = decoded.format,
                cloud = cloud,
                render = RenderScene(packed, cloud.positions, framing.radius),
                framing = framing,
                shDropped = decoded.shDropped,
                sizeBytes = size,
            )
        }
    }
}

/** Counts the bytes read; [drain] reads the rest and returns the stream's whole length. */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }

    /** Skips by reading, so a skip past the end is not counted. */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(minOf(n, DRAIN_BUFFER.toLong()).toInt())
        var left = n
        while (left > 0) {
            val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) break
            left -= read
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    fun drain(): Long {
        val buffer = ByteArray(DRAIN_BUFFER)
        while (read(buffer, 0, buffer.size) >= 0) Unit
        return count
    }

    private companion object {
        const val DRAIN_BUFFER = 8192
    }
}
