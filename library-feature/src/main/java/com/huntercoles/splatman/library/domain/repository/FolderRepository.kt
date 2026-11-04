package com.huntercoles.splatman.library.domain.repository

import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import kotlinx.coroutines.flow.Flow

/**
 * The Library's folders (ADR 0013 points 5-9): rows only, so no folder action touches a file under `scans/` or
 * deletes a scan. A name is trimmed and must be 1..[MAX_NAME_LENGTH] characters (code points), else
 * [IllegalArgumentException]; a name whose `LibraryText.key` another folder has throws [FolderNameTaken].
 */
interface FolderRepository {

    /** Every folder, newest first; emits again on every create, rename, delete and restore. */
    fun folders(): Flow<List<LibraryFolder>>

    suspend fun create(name: String): LibraryFolder

    /** Renames folder [id]; its own current name is not taken. A missing folder throws [NoSuchElementException]. */
    suspend fun rename(id: String, name: String): LibraryFolder

    /**
     * Moves the folder's scans to the Library root and removes the folder, in one transaction. A missing folder
     * throws [NoSuchElementException].
     */
    suspend fun delete(id: String): DeletedFolder

    /** Puts [deleted] back with the same id, name and date and moves back the scans that still exist. */
    suspend fun restore(deleted: DeletedFolder)

    /**
     * Puts the scans [scanIds] in folder [folderId] (null = the Library root) in one transaction and returns each
     * existing id's previous place. A [folderId] that no longer exists throws [NoSuchElementException]; nothing moves.
     */
    suspend fun move(scanIds: Collection<String>, folderId: String?): Map<String, String?>

    /** Undoes [move]: each scan goes back to its place; a place whose folder is gone becomes the root. */
    suspend fun restorePlaces(places: Map<String, String?>)

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/** Another folder already has a name with the same `LibraryText.key` as [name]. */
class FolderNameTaken(val name: String) : Exception("A folder named \"$name\" already exists")
