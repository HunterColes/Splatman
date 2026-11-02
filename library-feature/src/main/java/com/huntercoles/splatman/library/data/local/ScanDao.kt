package com.huntercoles.splatman.library.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Query("SELECT * FROM scans ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans")
    suspend fun all(): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun byId(id: String): ScanEntity?

    /** Returns -1 when a row with the same id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(scan: ScanEntity): Long

    @Update
    suspend fun update(scan: ScanEntity)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: String)
}
