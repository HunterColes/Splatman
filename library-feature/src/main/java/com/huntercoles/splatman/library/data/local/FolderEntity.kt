package com.huntercoles.splatman.library.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.huntercoles.splatman.library.domain.LibraryText
import com.huntercoles.splatman.library.domain.model.LibraryFolder

/**
 * A Library folder (ADR 0013 point 1): one level, a row only, no directory on disk. [nameKey] is [LibraryText.key]
 * of [name] and is unique, so two names that differ only by case or accent cannot both exist.
 */
@Entity(tableName = "folders", indices = [Index(value = ["nameKey"], name = "index_folders_nameKey", unique = true)])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val createdAtMs: Long,
) {
    fun toFolder() = LibraryFolder(id, name, createdAtMs)

    companion object {
        /** The row of [folder], its [nameKey] computed from the name. */
        fun from(folder: LibraryFolder) =
            FolderEntity(folder.id, folder.name, LibraryText.key(folder.name), folder.createdAtMs)
    }
}
