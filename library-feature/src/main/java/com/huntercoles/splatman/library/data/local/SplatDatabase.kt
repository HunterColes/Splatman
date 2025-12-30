package com.huntercoles.splatman.library.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for storing Gaussian splat scene metadata.
 * 
 * The actual Gaussian data is stored in binary files (.splat) for performance.
 * This database only stores metadata for quick queries, sorting, and library UI.
 * 
 * Version 1: Initial schema with SplatSceneEntity
 */
@Database(
    entities = [SplatSceneEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SplatDatabase : RoomDatabase() {
    abstract fun splatSceneDao(): SplatSceneDao
    
    companion object {
        const val DATABASE_NAME = "splat_database"
    }
}
