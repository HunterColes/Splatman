package com.huntercoles.splatman.library.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Library folders and which scans sit in them (ADR 0013 points 1, 6 and 7). Folder actions never delete a scan. */
@Dao
abstract class FolderDao {

    @Query("SELECT * FROM folders ORDER BY createdAtMs DESC, id")
    abstract fun observeAll(): Flow<List<FolderEntity>>

    /** Throws `SQLiteConstraintException` when the id or the [FolderEntity.nameKey] is already taken. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(folder: FolderEntity)

    /** Renames a folder; [nameKey] must be the key of [name]. Throws on a taken key; returns the rows changed. */
    @Query("UPDATE folders SET name = :name, nameKey = :nameKey WHERE id = :id")
    abstract suspend fun updateName(id: String, name: String, nameKey: String): Int

    @Query("SELECT * FROM folders WHERE id = :id")
    abstract suspend fun byId(id: String): FolderEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :id)")
    abstract suspend fun exists(id: String): Boolean

    @Query("DELETE FROM folders WHERE id = :id")
    abstract suspend fun delete(id: String): Int

    /** Moves the folder's scans to the Library root, then deletes the folder row; returns those scans' ids. */
    @Transaction
    open suspend fun deleteKeepingScans(id: String): List<String> {
        val ids = scanIds(id)
        clearFolder(id)
        delete(id)
        return ids
    }

    /** Puts the scans [ids] in folder [folderId] (null = the Library root); returns the rows changed. */
    @Query("UPDATE scans SET folderId = :folderId WHERE id IN (:ids)")
    abstract suspend fun setFolder(ids: List<String>, folderId: String?): Int

    /** The rows of those scans [ids] that exist. */
    @Query("SELECT * FROM scans WHERE id IN (:ids)")
    abstract suspend fun scans(ids: List<String>): List<ScanEntity>

    @Query("SELECT id FROM scans WHERE folderId = :folderId")
    abstract suspend fun scanIds(folderId: String): List<String>

    @Query("UPDATE scans SET folderId = NULL WHERE folderId = :folderId")
    protected abstract suspend fun clearFolder(folderId: String): Int
}
