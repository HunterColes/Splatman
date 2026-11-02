package com.huntercoles.splatman.library.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huntercoles.splatman.library.domain.model.Scan
import com.huntercoles.splatman.library.domain.model.ScanStatus

/** A scan's row; [folder] is the folder name under the scans root. [status] is stored as its name. */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val status: ScanStatus,
    val splatCount: Int,
    val keyframeCount: Int,
    val folder: String,
) {
    fun toScan() = Scan(id, name, createdAtMs, status, splatCount, keyframeCount, folder)
}
