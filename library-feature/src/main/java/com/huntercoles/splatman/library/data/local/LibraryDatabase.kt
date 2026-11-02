package com.huntercoles.splatman.library.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The library's database. Version 1 held the old point-cloud scenes; version 2 is the scan table, reached from 1 by
 * a destructive migration (the folders are the truth, and `ScanRepository.sync` adopts them again). Later versions
 * need real migrations.
 */
@Database(entities = [ScanEntity::class], version = LibraryDatabase.VERSION, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        const val NAME = "splat_database"
        const val VERSION = 2
        const val OLD_POINT_CLOUD_VERSION = 1
    }
}
