package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import com.huntercoles.splatman.splat.io.ply.PlyWriter
import com.huntercoles.splatman.splat.model.SplatCloud
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.InputStream

/** A file handed to the import flow: its display name and a way to read it (again). */
interface ImportSource {
    val fileName: String

    /** A fresh stream of the whole file; the caller closes it. Blocking. */
    fun open(): InputStream
}

/** Validates and saves one file; [SplatImporter] is the real one. */
fun interface Importer {
    suspend fun import(source: ImportSource): ImportOutcome
}

/** How an import ended: the new Library entry, or the validation that refused the file. */
sealed interface ImportOutcome {
    data class Imported(val scan: Scan) : ImportOutcome

    data class Refused(val validation: ImportValidation) : ImportOutcome
}

/**
 * The import flow's work (docs/design/screens/import.md): validate the file, check there is room for its copy, then
 * save it as a new imported scan (`scans/<id>/splat.ply`, the format the viewer opens) named after the file.
 * [freeBytes] is the space left where scans live. Cancellable: a cancel stops the read and leaves no folder behind.
 */
class SplatImporter(
    private val validator: ImportValidator,
    private val scans: ScanRepository,
    private val freeBytes: () -> Long,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) : Importer {

    override suspend fun import(source: ImportSource): ImportOutcome {
        val validation = runInterruptible(io) { validator.validate(source.fileName, source::open) }
        if (validation !is ImportValidation.Valid) return ImportOutcome.Refused(validation)
        val cloud = validation.cloud
        val needed = plyBytes(cloud.count, cloud.shDegree)
        val free = runInterruptible(io) { freeBytes() }
        if (needed > free) return ImportOutcome.Refused(ImportValidation.NoSpace(needed, free))
        val scan = scans.import(validation.name, now(), cloud.count) { out -> PlyWriter.write(cloud, out) }
        return ImportOutcome.Imported(scan)
    }

    companion object {
        /** The size of the PLY [PlyWriter] writes for [count] splats at [shDegree]. */
        fun plyBytes(count: Int, shDegree: Int): Long {
            val rest = 3 * SplatCloud.restCoefficients(shDegree)
            val stride = (PLY_FIXED_FLOATS + rest) * Float.SIZE_BYTES
            return PlyWriter.header(count, shDegree).length + count.toLong() * stride
        }

        /** x y z, nx ny nz, f_dc 0-2, opacity, scale 0-2, rot 0-3. */
        private const val PLY_FIXED_FLOATS = 17
    }
}
