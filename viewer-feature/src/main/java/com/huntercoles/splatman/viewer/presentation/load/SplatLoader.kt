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

/** A loaded file, ready for the renderer: the decoded cloud, its packed GPU form and the starting camera. */
class LoadedScene(
    val name: String,
    val format: SplatFormat,
    val cloud: SplatCloud,
    val render: RenderScene,
    val framing: SceneFraming,
    val shDropped: Boolean,
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
        val (name, decoded) = runInterruptible(io) {
            val name = opener.displayName(source)
            name to opener.open(source).use { decoder.decode(it) }
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
            )
        }
    }
}
