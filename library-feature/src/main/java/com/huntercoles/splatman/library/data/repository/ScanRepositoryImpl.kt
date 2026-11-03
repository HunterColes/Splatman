package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.data.format.SplatFileHeader
import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.local.ScanEntity
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Scans as folders under [root] (ADR 0007: `transforms.json`, `images/`, `seed.ply`, `splat.ply`) with a [dao] row
 * each. The folder name is the scan id for scans made here and for adopted folders.
 */
class ScanRepositoryImpl(
    private val dao: ScanDao,
    private val root: File,
    private val canceller: ProcessingCanceller,
    private val io: CoroutineDispatcher,
) : ScanRepository {

    override fun scans(): Flow<List<Scan>> = dao.observeAll().map { rows -> rows.map(ScanEntity::toScan) }

    override suspend fun get(id: String): Scan? = dao.byId(id)?.toScan()

    override suspend fun create(name: String, createdAtMs: Long): Scan {
        val id = UUID.randomUUID().toString()
        withContext(io) {
            if (!folderOf(id).mkdirs()) throw IOException("Could not create the scan folder $id")
        }
        val row = ScanEntity(id, name, createdAtMs, ScanStatus.CAPTURED, 0, 0, id)
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
        try {
            withContext(io) {
                if (!folder.mkdirs()) throw IOException("Could not create the scan folder $id")
                val temp = File(folder, "$SPLAT_FILE.tmp")
                temp.outputStream().buffered().use(writeSplat)
                if (!temp.renameTo(File(folder, SPLAT_FILE))) throw IOException("Could not save the splat of $id")
            }
            val row = ScanEntity(id, name, createdAtMs, ScanStatus.READY, splatCount, 0, id, imported = true)
            dao.insert(row)
            return row.toScan()
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { folder.deleteRecursively() }
            throw e
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
        val folders = withContext(io) {
            root.mkdirs()
            root.listFiles()?.filter { it.isDirectory }.orEmpty().associateBy { it.name }
        }
        val rows = dao.all()
        rows.filter { it.folder !in folders }.forEach { dao.delete(it.id) }
        val known = rows.map { it.folder }.toSet()
        folders.values.filter { it.name !in known }.forEach { dao.insert(withContext(io) { adopt(it) }) }
    }

    override suspend fun refresh(id: String) {
        val row = dao.byId(id)
        val folder = folderOf(row?.folder ?: id)
        val found = withContext(io) { if (folder.isDirectory) adopt(folder) else null } ?: return
        if (row == null) {
            dao.insert(found.copy(id = id))
        } else {
            dao.update(
                row.copy(status = found.status, splatCount = found.splatCount, keyframeCount = found.keyframeCount),
            )
        }
    }

    /**
     * A row for a folder found on disk: READY when its `splat.ply` reads, CAPTURED with only a bundle, else FAILED.
     * A READY folder without `transforms.json` came from the import flow.
     */
    private fun adopt(folder: File): ScanEntity {
        val splat = File(folder, SPLAT_FILE)
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
            !splat.exists() && File(folder, TRANSFORMS_FILE).isFile -> ScanStatus.CAPTURED
            else -> ScanStatus.FAILED
        }
        val keyframes = File(folder, IMAGES_DIR).listFiles()?.count { it.isFile } ?: 0
        return ScanEntity(
            id = folder.name,
            name = folder.name,
            createdAtMs = folder.lastModified(),
            status = status,
            splatCount = splatCount ?: 0,
            keyframeCount = keyframes,
            folder = folder.name,
            imported = status == ScanStatus.READY && !File(folder, TRANSFORMS_FILE).exists(),
        )
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
        const val IMAGES_DIR = "images"
    }
}
