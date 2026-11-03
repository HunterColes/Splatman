package com.huntercoles.splatman.library.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The library's database. Version 1 held the old point-cloud scenes; version 2 is the scan table, reached from 1 by
 * a destructive migration (the folders are the truth, and `ScanRepository.sync` adopts them again). Later versions
 * need real migrations: 2 -> 3 adds `imported` ([MIGRATION_2_3]).
 */
@Database(entities = [ScanEntity::class], version = LibraryDatabase.VERSION, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        const val NAME = "splat_database"
        const val VERSION = 3
        const val OLD_POINT_CLOUD_VERSION = 1
    }
}

/** Version 3: imported scans carry `imported = 1`; every older row was made on this phone. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_IMPORTED_SQL)
    }
}

/** The SQL of [MIGRATION_2_3]; the column matches [ScanEntity.imported]'s `defaultValue`. */
const val ADD_IMPORTED_SQL = "ALTER TABLE scans ADD COLUMN imported INTEGER NOT NULL DEFAULT 0"
