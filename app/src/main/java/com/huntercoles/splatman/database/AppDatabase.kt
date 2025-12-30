package com.huntercoles.splatman.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.huntercoles.splatman.database.entity.AppMetadata

private const val DATABASE_VERSION = 1

@Database(
    entities = [AppMetadata::class],
    version = DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    // Splat-specific DAOs will be added here as needed
}
