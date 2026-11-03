package com.huntercoles.splatman.library.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus

/**
 * A scan's row. [folder] is the scan's directory name under the scans root (ADR 0007, on disk); [folderId] is the
 * Library folder row ([FolderEntity.id]) the scan shows in, null at the Library root (ADR 0013). [status] is stored
 * as its name.
 */
@Entity(tableName = "scans", indices = [Index(value = ["folderId"], name = "index_scans_folderId")])
data class ScanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val status: ScanStatus,
    val splatCount: Int,
    val keyframeCount: Int,
    val folder: String,
    @ColumnInfo(defaultValue = "0") val imported: Boolean = false,
    val folderId: String? = null,
) {
    fun toScan() = Scan(id, name, createdAtMs, status, splatCount, keyframeCount, folder, imported, folderId = folderId)

    companion object {
        /** The row of [scan]; what is read from disk ([Scan.sizeBytes], [Scan.thumbnail]) is not stored. */
        fun from(scan: Scan) = ScanEntity(
            scan.id,
            scan.name,
            scan.createdAtMs,
            scan.status,
            scan.splatCount,
            scan.keyframeCount,
            scan.folder,
            scan.imported,
            scan.folderId,
        )
    }
}
