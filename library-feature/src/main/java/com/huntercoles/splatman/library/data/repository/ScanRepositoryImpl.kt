package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.data.format.SplatFileHeader
import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.local.ScanEntity
import com.huntercoles.splatman.library.domain.LandingFolder
import com.huntercoles.splatman.library.domain.ScanNamer
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Scans as folders under [root] (ADR 0007: `transforms.json` or the pose-free `capture.json`, `images/`, `seed.ply`,
 * `splat.ply`) with a [dao] row each. The folder name is the scan id for scans made here and for adopted folders.
 * Rows made by [create], [import] and the [refresh] adoption of a new folder land in [landing]'s Library folder when
 * [folderExists] says it still exists, else at the root; [sync] adopts at the root (ADR 0013 point 8). A captured
 * folder adopted without a row is named once by [namer] (copy.md `scan_default_name`, #426); an imported one keeps
 * its folder name.
 */
class ScanRepositoryImpl(
    private val dao: ScanDao,
    private val root: File,
    private val canceller: ProcessingCanceller,
    private val io: CoroutineDispatcher,
    private val landing: LandingFolder = LandingFolder(),
    private val namer: ScanNamer = ScanNamer { id, _ -> id },
    private val folderExists: suspend (String) -> Boolean = { false },
) : ScanRepository {

    /** The Library folder a new row lands in: [landing]'s when it still exists, else null (the root). */
    private suspend fun landingFolderId(): String? = landing.folderId.value?.takeIf { folderExists(it) }

    /**
     * Every collection starts with a [sync]: a foss Stop renames its folder in with no row and no processing run
     * (#341), so the Library adopts it the next time it looks, not only when its view model is first made.
     */
    override fun scans(): Flow<List<Scan>> = flow {
        try {
            sync()
        } catch (e: IOException) {
            Timber.w(e, "Scan folder sync failed")
        }
        emitAll(dao.observeAll().map { rows -> rows.map { it.toScan().onDisk() } })
    }.flowOn(io)

    override suspend fun get(id: String): Scan? {
        val row = dao.byId(id) ?: return null
        return withContext(io) { row.toScan().onDisk() }
    }

    override suspend fun create(name: String, createdAtMs: Long): Scan {
        val id = UUID.randomUUID().toString()
        withContext(io) {
            if (!folderOf(id).mkdirs()) throw IOException("Could not create the scan folder $id")
        }
        val row = ScanEntity(id, name, createdAtMs, ScanStatus.CAPTURED, 0, 0, id, folderId = landingFolderId())
        dao.insert(row)
        return row.toScan()
    }

    override suspend fun updateStatus(id: String, status: ScanStatus, splatCount: Int?, keyframeCount: Int?) {
        val row = dao.byId(id) ?: return
        dao.update(
            row.copy(
                status = status,
                splatCount = splatCount ?: row.splatCount,
                keyframeCount = keyframeCount ?: row.keyframeCount,
            ),
        )
    }

    override suspend fun import(
        name: String,
        createdAtMs: Long,
        splatCount: Int,
        writeSplat: (OutputStream) -> Unit,
    ): Scan {
        val id = UUID.randomUUID().toString()
        val folder = folderOf(id)
        var imported = false
        try {
            withContext(io) {
                if (!folder.mkdirs()) throw IOException("Could not create the scan folder $id")
                val temp = File(folder, "$SPLAT_FILE.tmp")
                temp.outputStream().buffered().use(writeSplat)
                if (!temp.renameTo(File(folder, SPLAT_FILE))) throw IOException("Could not save the splat of $id")
            }
            val row = ScanEntity(
                id, name, createdAtMs, ScanStatus.READY, splatCount, 0, id,
                imported = true,
                folderId = landingFolderId(),
            )
            dao.insert(row)
            val scan = withContext(io) { row.toScan().onDisk() }
            imported = true
            return scan
        } finally {
            if (!imported) withContext(NonCancellable + io) { folder.deleteRecursively() }
        }
    }

    override suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val row = dao.byId(id) ?: return
        dao.update(row.copy(name = trimmed))
    }

    override suspend fun delete(id: String) {
        val row = dao.byId(id) ?: return
        if (row.status == ScanStatus.PROCESSING) canceller.cancel(id)
        withContext(io) {
            val folder = folderOf(row.folder)
            if (folder.exists() && !folder.deleteRecursively()) throw IOException("Could not delete scan ${row.id}")
        }
        dao.delete(id)
    }

    override suspend fun sync() {
        // Rows before folders: a scan created between the two reads is then listed, never dropped as gone.
        val rows = dao.all()
        val folders = withContext(io) {
            root.mkdirs()
            root.listFiles()?.filter { it.isDirectory }.orEmpty().associateBy { it.name }
        }
        rows.filter { it.folder !in folders }.forEach { dao.delete(it.id) }
        val known = rows.map { it.folder }.toSet()
        folders.values.filter { it.name !in known }.forEach { folder ->
            withContext(io) { adopt(folder) }?.let { dao.insert(it) }
        }
    }

    override suspend fun refresh(id: String) {
        val row = dao.byId(id)
        val folder = folderOf(row?.folder ?: id)
        val found = withContext(io) { if (folder.isDirectory) adopt(folder) else null } ?: return
        if (row == null) {
            dao.insert(found.copy(id = id, folderId = landingFolderId()))
        } else {
            dao.update(
                row.copy(status = found.status, splatCount = found.splatCount, keyframeCount = found.keyframeCount),
            )
        }
    }

    /**
     * A row for a folder found on disk: READY when its `splat.ply` reads, CAPTURED with only a bundle
     * (`transforms.json` or `capture.json`), else FAILED. A READY folder without either came from the import flow.
     * Null for a folder with none of the three yet (a capture still being written): it stays hidden until it is one.
     */
    private fun adopt(folder: File): ScanEntity? {
        val splat = File(folder, SPLAT_FILE)
        val bundle = File(folder, TRANSFORMS_FILE).isFile || File(folder, CAPTURE_FILE).isFile
        if (!splat.exists() && !bundle) return null
        val splatCount = if (splat.isFile) {
            try {
                splat.inputStream().buffered().use { SplatFileHeader.readPly(it).splatCount }
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
        val status = when {
            splatCount != null -> ScanStatus.READY
            !splat.exists() && bundle -> ScanStatus.CAPTURED
            else -> ScanStatus.FAILED
        }
        val keyframes = File(folder, IMAGES_DIR).listFiles()?.count { it.isFile } ?: 0
        val createdAtMs = folder.lastModified()
        val imported = status == ScanStatus.READY && !File(folder, TRANSFORMS_FILE).exists() &&
            !File(folder, CAPTURE_FILE).exists()
        return ScanEntity(
            id = folder.name,
            name = if (imported) folder.name else namer.name(folder.name, createdAtMs),
            createdAtMs = createdAtMs,
            status = status,
            splatCount = splatCount ?: 0,
            keyframeCount = keyframes,
            folder = folder.name,
            imported = imported,
        )
    }

    /** The scan with what its folder holds: its size in bytes and its non-empty `thumbnail.png`. Blocking. */
    private fun Scan.onDisk(): Scan {
        val dir = File(root, folder)
        if (!dir.isDirectory) return this
        val picture = File(dir, THUMBNAIL_FILE)
        val thumbnail = if (picture.isFile && picture.length() > 0) {
            ScanThumbnail(picture.path, picture.lastModified())
        } else {
            null
        }
        val bytes = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return copy(sizeBytes = bytes, thumbnail = thumbnail)
    }

    /** The folder [name] under [root]; refuses a name that would leave it. */
    private fun folderOf(name: String): File {
        val folder = File(root, name)
        if (folder.canonicalFile.parentFile != root.canonicalFile) throw IOException("Not a scan folder: $name")
        return folder
    }

    companion object {
        const val SPLAT_FILE = "splat.ply"
        const val TRANSFORMS_FILE = "transforms.json"
        const val CAPTURE_FILE = "capture.json"
        const val IMAGES_DIR = "images"
        const val THUMBNAIL_FILE = "thumbnail.png"
    }
}
