package com.huntercoles.splatman.library.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The library's database. Version 1 held the old point-cloud scenes; version 2 is the scan table, reached from 1 by
 * a destructive migration (the folders are the truth, and `ScanRepository.sync` adopts them again). Later versions
 * need real migrations: 2 -> 3 adds `imported` ([MIGRATION_2_3]), 3 -> 4 adds Library folders ([MIGRATION_3_4],
 * ADR 0013).
 */
@Database(entities = [ScanEntity::class, FolderEntity::class], version = LibraryDatabase.VERSION, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    abstract fun folderDao(): FolderDao

    companion object {
        const val NAME = "splat_database"
        const val VERSION = 4
        const val OLD_POINT_CLOUD_VERSION = 1
    }
}

/** Version 3: imported scans carry `imported = 1`; every older row was made on this phone. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_IMPORTED_SQL)
    }
}

/**
 * Version 4 (ADR 0013 point 4): the `folders` table and `scans.folderId`. Every existing row lands at the Library
 * root (`folderId` null) with its other columns unchanged; no folder is created.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_FOLDERS_SQL)
        db.execSQL(CREATE_FOLDERS_NAME_KEY_INDEX_SQL)
        db.execSQL(ADD_FOLDER_ID_SQL)
        db.execSQL(CREATE_SCANS_FOLDER_ID_INDEX_SQL)
    }
}

/** The SQL of [MIGRATION_2_3]; the column matches [ScanEntity.imported]'s `defaultValue`. */
const val ADD_IMPORTED_SQL = "ALTER TABLE scans ADD COLUMN imported INTEGER NOT NULL DEFAULT 0"

/** The SQL of [MIGRATION_3_4], in its order; it matches [FolderEntity] and [ScanEntity.folderId] as Room checks. */
const val CREATE_FOLDERS_SQL = "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
    "`nameKey` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"
const val CREATE_FOLDERS_NAME_KEY_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_nameKey` ON `folders` (`nameKey`)"
const val ADD_FOLDER_ID_SQL = "ALTER TABLE scans ADD COLUMN folderId TEXT"
const val CREATE_SCANS_FOLDER_ID_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_scans_folderId` ON `scans` (`folderId`)"
