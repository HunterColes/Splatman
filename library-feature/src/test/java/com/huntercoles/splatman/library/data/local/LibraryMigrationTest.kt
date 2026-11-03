package com.huntercoles.splatman.library.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.ScanStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #309: DB v3 -> v4 (ADR 0013 point 4). A v3 (or v2) file database is written by hand with the schema Room made for
 * it, then opened through Room with the app's migrations; Room validates the v4 schema on open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: LibraryDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(NAME)
    }

    @After
    fun close() {
        database?.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `A1 every v3 row comes back unchanged at the root and no folder is made`() = runBlocking {
        val rows = listOf(
            ScanEntity("made", "Desk plant", 10, ScanStatus.READY, 1200, 40, "made"),
            ScanEntity("imp", "Cactus", 20, ScanStatus.READY, 300, 0, "imp", imported = true),
            ScanEntity("bad", "Broken", 30, ScanStatus.FAILED, 0, 12, "bad"),
            ScanEntity("busy", "Kitchen", 40, ScanStatus.PROCESSING, 0, 55, "busy"),
        )
        writeOld(3, rows)

        val db = open()

        assertEquals(rows.sortedBy { it.id }, db.scanDao().all().sortedBy { it.id })
        assertTrue(db.scanDao().all().all { it.folderId == null })
        assertEquals(emptyList<FolderEntity>(), db.folderDao().observeAll().first())
        assertEquals(4, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `H1 a v3 database with zero rows migrates`() = runBlocking {
        writeOld(3, emptyList())

        val db = open()

        assertEquals(emptyList<ScanEntity>(), db.scanDao().all())
        assertEquals(emptyList<FolderEntity>(), db.folderDao().observeAll().first())
    }

    @Test
    fun `H4 a v2 database still migrates 2 to 3 to 4`() = runBlocking {
        writeOld(2, listOf(ScanEntity("old", "Old scan", 5, ScanStatus.READY, 9, 3, "old")))

        val db = open()

        assertEquals(listOf(ScanEntity("old", "Old scan", 5, ScanStatus.READY, 9, 3, "old")), db.scanDao().all())
    }

    @Test
    fun `A1 two folder names that differ only by case or accent break the unique key`() = runBlocking {
        writeOld(3, emptyList())
        val folders = open().folderDao()
        folders.insert(FolderEntity.from(LibraryFolder("a", "Café", 1)))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { folders.insert(FolderEntity.from(LibraryFolder("b", "CAFE", 2))) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { folders.insert(FolderEntity.from(LibraryFolder("c", " cafe ", 3))) }
        }
        assertEquals(listOf("a"), folders.observeAll().first().map { it.id })
    }

    @Test
    fun `H2 H3 emoji-only and right-to-left folder names are stored as typed`() = runBlocking {
        writeOld(3, emptyList())
        val folders = open().folderDao()
        val names = listOf("🌵🏠", "שלום", "مرحبا")
        names.forEachIndexed { i, name -> folders.insert(FolderEntity.from(LibraryFolder("f$i", name, i.toLong()))) }

        val stored = folders.observeAll().first().sortedBy { it.id }
        assertEquals(names, stored.map { it.toFolder().name })
        assertEquals(names, stored.map { it.nameKey })
    }

    @Test
    fun `A2 folder rows move scans, rename, and delete keeping their scans`() = runBlocking {
        writeOld(
            3,
            listOf(
                ScanEntity("s1", "One", 1, ScanStatus.READY, 1, 1, "s1"),
                ScanEntity("s2", "Two", 2, ScanStatus.PROCESSING, 0, 1, "s2"),
                ScanEntity("s3", "Three", 3, ScanStatus.READY, 1, 1, "s3"),
            ),
        )
        val db = open()
        val folders = db.folderDao()
        folders.insert(FolderEntity.from(LibraryFolder("f", "Plants", 1)))
        folders.insert(FolderEntity.from(LibraryFolder("g", "Rooms", 2)))

        assertEquals(2, folders.setFolder(listOf("s1", "s2"), "f"))
        assertEquals(listOf("s1", "s2"), folders.scanIds("f").sorted())
        assertEquals("f", db.scanDao().byId("s2")!!.toScan().folderId)

        assertEquals(1, folders.updateName("f", "Cacti", "cacti"))
        assertEquals("Cacti", folders.observeAll().first().single { it.id == "f" }.name)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { folders.updateName("f", "ROOMS", "rooms") }
        }

        assertEquals(listOf("s1", "s2"), folders.deleteKeepingScans("f").sorted())
        assertEquals(listOf("g"), folders.observeAll().first().map { it.id })
        assertEquals(3, db.scanDao().all().size)
        assertTrue(db.scanDao().all().all { it.folderId == null })

        assertEquals(1, folders.setFolder(listOf("s3"), "g"))
        assertEquals(1, folders.setFolder(listOf("s3"), null))
        assertEquals(emptyList<String>(), folders.scanIds("g"))
    }

    @Test
    fun `A3 folderId maps between the row and the scan both ways`() {
        val row = ScanEntity("s", "S", 1, ScanStatus.READY, 2, 3, "s", imported = true, folderId = "f")
        assertEquals("f", row.toScan().folderId)
        assertEquals(row, ScanEntity.from(row.toScan()))
        assertEquals(null, ScanEntity.from(row.toScan().copy(folderId = null)).folderId)
    }

    private fun open(): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, NAME)
            .fallbackToDestructiveMigrationFrom(LibraryDatabase.OLD_POINT_CLOUD_VERSION)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()
            .also { database = it }

    /** A file database at [version] with the `scans` table Room created at that version, holding [rows]. */
    private fun writeOld(version: Int, rows: List<ScanEntity>) {
        val file = context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(if (version == 2) V2_SCANS_SQL else V3_SCANS_SQL)
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("id", row.id)
                    put("name", row.name)
                    put("createdAtMs", row.createdAtMs)
                    put("status", row.status.name)
                    put("splatCount", row.splatCount)
                    put("keyframeCount", row.keyframeCount)
                    put("folder", row.folder)
                    if (version >= 3) put("imported", if (row.imported) 1 else 0)
                }
                db.insertOrThrow("scans", null, values)
            }
            db.version = version
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val V2_SCANS_SQL = "CREATE TABLE IF NOT EXISTS `scans` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `splatCount` INTEGER NOT NULL, " +
            "`keyframeCount` INTEGER NOT NULL, `folder` TEXT NOT NULL, PRIMARY KEY(`id`))"
        const val V3_SCANS_SQL = "CREATE TABLE IF NOT EXISTS `scans` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `splatCount` INTEGER NOT NULL, " +
            "`keyframeCount` INTEGER NOT NULL, `folder` TEXT NOT NULL, " +
            "`imported` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
    }
}
