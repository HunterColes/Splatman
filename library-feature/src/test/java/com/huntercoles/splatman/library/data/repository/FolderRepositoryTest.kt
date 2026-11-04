package com.huntercoles.splatman.library.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huntercoles.splatman.library.data.local.LibraryDatabase
import com.huntercoles.splatman.library.data.local.ScanEntity
import com.huntercoles.splatman.library.domain.LandingFolder
import com.huntercoles.splatman.library.domain.model.DeletedFolder
import com.huntercoles.splatman.library.domain.model.LibraryFolder
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.FolderNameTaken
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** #311: [FolderRepositoryImpl] over an in-memory Room [LibraryDatabase] (ADR 0013 points 5-9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    private var clock = 1_000L
    private val repository = FolderRepositoryImpl(db, now = { clock++ })
    private val scans = db.scanDao()

    @After
    fun close() {
        db.close()
    }

    // 1. Names

    @Test
    fun `create trims the name and rejects blank and over 60 characters`() = runBlocking {
        val folder = repository.create("  Plants \n")

        assertEquals("Plants", folder.name)
        assertEquals(listOf(folder), repository.folders().first())
        for (bad in listOf("", "   ", "\t\n", "x".repeat(61))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.create(bad) } }
        }
        assertEquals(60, repository.create("y".repeat(60)).name.length)
        assertEquals(2, repository.folders().first().size)
    }

    @Test
    fun `H names differing only by case or accent are taken`() = runBlocking {
        repository.create("Café")

        for (same in listOf("cafe", "CAFE", " CAFÉ ", "Café")) {
            assertThrows(FolderNameTaken::class.java) { runBlocking { repository.create(same) } }
        }
        assertEquals(listOf("Café"), repository.folders().first().map { it.name })
    }

    @Test
    fun `H emoji and right-to-left names are kept as typed and still clash with themselves`() = runBlocking {
        val names = listOf("🌵🏠", "שלום", "مرحبا")
        names.forEach { repository.create(" $it ") }

        assertEquals(names.toSet(), repository.folders().first().map { it.name }.toSet())
        assertThrows(FolderNameTaken::class.java) { runBlocking { repository.create("שלום") } }
        // 60 emoji are 120 UTF-16 units but 60 characters.
        assertEquals("🌵".repeat(60), repository.create("🌵".repeat(60)).name)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.create("🌵".repeat(61)) } }
        Unit
    }

    @Test
    fun `rename has the same rules and a folder's own name is not taken`() = runBlocking {
        val plants = repository.create("Plants")
        repository.create("Rooms")

        assertEquals("PLANTS", repository.rename(plants.id, "  PLANTS ").name)
        assertEquals("Plantés", repository.rename(plants.id, "Plantés").name)
        assertThrows(FolderNameTaken::class.java) { runBlocking { repository.rename(plants.id, "rooms") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.rename(plants.id, " ") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.rename(plants.id, "z".repeat(61)) }
        }
        assertThrows(NoSuchElementException::class.java) { runBlocking { repository.rename("gone", "Fine") } }

        val after = repository.folders().first().single { it.id == plants.id }
        assertEquals(LibraryFolder(plants.id, "Plantés", plants.createdAtMs), after)
    }

    // 2. Delete and restore

    @Test
    fun `delete moves the folder's scans to the root, keeps files, and restore puts everything back`() =
        runBlocking {
            val root = scanTree("a", "b", "c")
            val before = snapshot(root)
            val plants = repository.create("Plants")
            insertScans("a" to ScanStatus.READY, "b" to ScanStatus.PROCESSING, "c" to ScanStatus.FAILED)
            repository.move(listOf("a", "b"), plants.id)

            val deleted = repository.delete(plants.id)

            assertEquals(plants, deleted.folder)
            assertEquals(listOf("a", "b"), deleted.scanIds.sorted())
            assertEquals(emptyList<LibraryFolder>(), repository.folders().first())
            assertTrue(scans.all().all { it.folderId == null })
            assertEquals(ScanStatus.PROCESSING, scans.byId("b")!!.status)
            assertEquals(3, scans.all().size)
            assertEquals(before, snapshot(root))

            repository.restore(deleted)

            assertEquals(listOf(plants), repository.folders().first())
            assertEquals(plants.id, scans.byId("a")!!.folderId)
            assertEquals(plants.id, scans.byId("b")!!.folderId)
            assertNull(scans.byId("c")!!.folderId)
            assertEquals(before, snapshot(root))
        }

    @Test
    fun `H restore after one of the scans was deleted moves back the rest`() = runBlocking {
        val plants = repository.create("Plants")
        insertScans("a" to ScanStatus.READY, "b" to ScanStatus.READY)
        repository.move(listOf("a", "b"), plants.id)
        val deleted = repository.delete(plants.id)
        scans.delete("a")

        repository.restore(deleted)

        assertNull(scans.byId("a"))
        assertEquals(plants.id, scans.byId("b")!!.folderId)
        assertEquals(listOf(plants), repository.folders().first())
    }

    @Test
    fun `restore over a name taken meanwhile throws FolderNameTaken and changes nothing`() = runBlocking {
        val plants = repository.create("Plants")
        insertScans("a" to ScanStatus.READY)
        repository.move(listOf("a"), plants.id)
        val deleted = repository.delete(plants.id)
        val other = repository.create("PLANTS")

        assertThrows(FolderNameTaken::class.java) { runBlocking { repository.restore(deleted) } }
        assertEquals(listOf(other), repository.folders().first())
        assertNull(scans.byId("a")!!.folderId)
    }

    @Test
    fun `deleting a missing folder throws`() {
        assertThrows(NoSuchElementException::class.java) { runBlocking { repository.delete("nowhere") } }
    }

    // 3. Move

    @Test
    fun `move sets folderId, returns previous places, keeps files, and restorePlaces undoes it`() = runBlocking {
        val root = scanTree("a", "b", "c")
        val before = snapshot(root)
        val plants = repository.create("Plants")
        val rooms = repository.create("Rooms")
        insertScans("a" to ScanStatus.READY, "b" to ScanStatus.PROCESSING, "c" to ScanStatus.FAILED)
        repository.move(listOf("a"), rooms.id)

        val places = repository.move(listOf("a", "b", "c", "missing"), plants.id)

        assertEquals(mapOf("a" to rooms.id, "b" to null, "c" to null), places)
        assertTrue(scans.all().all { it.folderId == plants.id })
        assertEquals(ScanStatus.PROCESSING, scans.byId("b")!!.status)
        assertEquals(ScanStatus.FAILED, scans.byId("c")!!.status)
        assertEquals(before, snapshot(root))

        repository.restorePlaces(places)

        assertEquals(rooms.id, scans.byId("a")!!.folderId)
        assertNull(scans.byId("b")!!.folderId)
        assertNull(scans.byId("c")!!.folderId)
        assertEquals(before, snapshot(root))

        assertEquals(mapOf("a" to rooms.id), repository.move(listOf("a"), null))
        assertNull(scans.byId("a")!!.folderId)
    }

    @Test
    fun `a move to a missing folder throws and nothing moves`() = runBlocking {
        val plants = repository.create("Plants")
        insertScans("a" to ScanStatus.READY, "b" to ScanStatus.READY)
        repository.move(listOf("a"), plants.id)

        assertThrows(NoSuchElementException::class.java) {
            runBlocking { repository.move(listOf("a", "b"), "gone") }
        }

        assertEquals(plants.id, scans.byId("a")!!.folderId)
        assertNull(scans.byId("b")!!.folderId)
    }

    @Test
    fun `restorePlaces puts a scan whose old folder is gone at the root`() = runBlocking {
        val plants = repository.create("Plants")
        val rooms = repository.create("Rooms")
        insertScans("a" to ScanStatus.READY)
        repository.move(listOf("a"), plants.id)
        val places = repository.move(listOf("a"), rooms.id)
        repository.delete(plants.id)

        repository.restorePlaces(places)

        assertNull(scans.byId("a")!!.folderId)
    }

    @Test
    fun `H a move of 500 ids moves them all and undoes`() = runBlocking {
        val plants = repository.create("Plants")
        val ids = (0 until 500).map { "s%03d".format(it) }
        ids.forEach { scans.insert(ScanEntity(it, it, 1, ScanStatus.READY, 1, 1, it)) }

        val places = repository.move(ids, plants.id)

        assertEquals(ids.toSet(), places.keys)
        assertEquals(500, scans.all().count { it.folderId == plants.id })
        repository.restorePlaces(places)
        assertTrue(scans.all().all { it.folderId == null })
        assertEquals(500, repository.move(ids + ids, plants.id).size)
    }

    // 4. Landing folder, through the real folder table

    @Test
    fun `new scans land in the open folder while it exists, else at the root, and sync adopts at the root`() =
        runBlocking {
            val root = tmp.newFolder("landing")
            val landing = LandingFolder()
            val folders = db.folderDao()
            val scanRepository = ScanRepositoryImpl(
                scans, root, ProcessingCanceller { }, Dispatchers.Unconfined, landing,
                folderExists = { folders.exists(it) },
            )
            val plants = repository.create("Plants")

            landing.set(plants.id)
            val made = scanRepository.create("made", 1)
            val imported = scanRepository.import("imported", 2, 0) { it.write(ByteArray(4)) }
            File(root, "fresh").apply { mkdirs() }.resolve("transforms.json").writeText("{}")
            scanRepository.refresh("fresh")
            File(root, "found").apply { mkdirs() }.resolve("transforms.json").writeText("{}")
            scanRepository.sync()

            assertEquals(plants.id, scans.byId(made.id)!!.folderId)
            assertEquals(plants.id, scans.byId(imported.id)!!.folderId)
            assertEquals(plants.id, scans.byId("fresh")!!.folderId)
            assertNull(scans.byId("found")!!.folderId)

            repository.delete(plants.id)
            assertNull(scanRepository.create("stale", 3).folderId)
            landing.set(null)
            assertNull(scanRepository.create("root", 4).folderId)
        }

    // 5. The folders flow

    @Test
    fun `folders emits on every create, rename, delete and restore`() = runBlocking {
        val seen = Channel<List<LibraryFolder>>(Channel.UNLIMITED)
        val job = CoroutineScope(Dispatchers.Default).launch { repository.folders().collect { seen.send(it) } }
        try {
            suspend fun next(expected: List<String>) = withTimeout(5_000) {
                while (seen.receive().map { it.name } != expected) Unit
            }
            next(emptyList())
            val plants = repository.create("Plants")
            next(listOf("Plants"))
            repository.create("Rooms")
            next(listOf("Rooms", "Plants"))
            repository.rename(plants.id, "Cacti")
            next(listOf("Rooms", "Cacti"))
            val deleted: DeletedFolder = repository.delete(plants.id)
            next(listOf("Rooms"))
            repository.restore(deleted)
            next(listOf("Rooms", "Cacti"))
        } finally {
            job.cancel()
        }
    }

    private suspend fun insertScans(vararg rows: Pair<String, ScanStatus>) {
        rows.forEach { (id, status) -> scans.insert(ScanEntity(id, id, 1, status, 0, 0, id)) }
    }

    /** A `scans/` tree with a folder per id holding a splat, a bundle and an image. */
    private fun scanTree(vararg ids: String): File {
        val root = tmp.newFolder("scans")
        ids.forEachIndexed { i, id ->
            val dir = File(root, id).apply { mkdirs() }
            File(dir, "splat.ply").writeBytes(ByteArray(16) { (it + i).toByte() })
            File(dir, "transforms.json").writeText("{\"id\":\"$id\"}")
            File(dir, "images").mkdirs()
            File(dir, "images/frame_00000.jpg").writeBytes(ByteArray(8) { i.toByte() })
        }
        return root
    }

    /** Every file under [root] by relative path, with its bytes, and every directory. */
    private fun snapshot(root: File): Map<String, List<Byte>?> =
        root.walkTopDown().filter { it != root }.associate {
            it.relativeTo(root).path to if (it.isFile) it.readBytes().toList() else null
        }
}
