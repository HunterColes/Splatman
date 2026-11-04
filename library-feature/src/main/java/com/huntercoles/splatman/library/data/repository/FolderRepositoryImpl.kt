package com.huntercoles.splatman.library.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.huntercoles.splatman.library.data.local.FolderEntity
import com.huntercoles.splatman.library.data.local.LibraryDatabase
import com.huntercoles.splatman.library.domain.LibraryText
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.repository.FolderNameTaken
import com.huntercoles.splatman.library.domain.repository.FolderRepository
import com.huntercoles.splatman.library.domain.repository.FolderRepository.Companion.MAX_NAME_LENGTH
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** [FolderRepository] over Room: every change that spans folders and scans runs in one transaction. */
class FolderRepositoryImpl(
    private val database: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : FolderRepository {

    private val folders = database.folderDao()

    override fun folders(): Flow<List<LibraryFolder>> =
        folders.observeAll().map { rows -> rows.map(FolderEntity::toFolder) }

    override suspend fun create(name: String): LibraryFolder {
        val folder = LibraryFolder(UUID.randomUUID().toString(), checkedName(name), now())
        insert(folder)
        return folder
    }

    override suspend fun rename(id: String, name: String): LibraryFolder {
        val trimmed = checkedName(name)
        val changed = try {
            folders.updateName(id, trimmed, LibraryText.key(trimmed))
        } catch (_: SQLiteConstraintException) {
            throw FolderNameTaken(trimmed)
        }
        if (changed == 0) throw NoSuchElementException("No folder $id")
        return folders.byId(id)?.toFolder() ?: throw NoSuchElementException("No folder $id")
    }

    override suspend fun delete(id: String): DeletedFolder = database.withTransaction {
        val folder = folders.byId(id)?.toFolder() ?: throw NoSuchElementException("No folder $id")
        DeletedFolder(folder, folders.deleteKeepingScans(id))
    }

    override suspend fun restore(deleted: DeletedFolder) {
        database.withTransaction {
            insert(deleted.folder)
            deleted.scanIds.chunked(CHUNK).forEach { folders.setFolder(it, deleted.folder.id) }
        }
    }

    override suspend fun move(scanIds: Collection<String>, folderId: String?): Map<String, String?> =
        database.withTransaction {
            if (folderId != null && !folders.exists(folderId)) throw NoSuchElementException("No folder $folderId")
            val ids = scanIds.distinct()
            val previous = ids.chunked(CHUNK).flatMap { folders.scans(it) }.associate { it.id to it.folderId }
            ids.chunked(CHUNK).forEach { folders.setFolder(it, folderId) }
            previous
        }

    override suspend fun restorePlaces(places: Map<String, String?>) {
        database.withTransaction {
            places.entries.groupBy({ it.value }, { it.key }).forEach { (folderId, ids) ->
                val place = folderId?.takeIf { folders.exists(it) }
                ids.chunked(CHUNK).forEach { folders.setFolder(it, place) }
            }
        }
    }

    /** Inserts [folder]; a taken name key (or id) is [FolderNameTaken]. */
    private suspend fun insert(folder: LibraryFolder) {
        try {
            folders.insert(FolderEntity.from(folder))
        } catch (_: SQLiteConstraintException) {
            throw FolderNameTaken(folder.name)
        }
    }

    private fun checkedName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A folder name cannot be blank" }
        require(trimmed.codePointCount(0, trimmed.length) <= MAX_NAME_LENGTH) {
            "A folder name is at most $MAX_NAME_LENGTH characters"
        }
        return trimmed
    }

    private companion object {
        /** Ids per `IN (...)`, under SQLite's oldest bound-variable limit (999). */
        const val CHUNK = 500
    }
}
