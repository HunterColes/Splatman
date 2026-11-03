package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.local.ScanEntity
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** [ScanRepositoryImpl] over a fake DAO (same contract as the Room one) and a real folder tree. */
class ScanRepositoryTest {

    @TempDir
    lateinit var tmp: File

    private val dao = FakeScanDao()
    private val cancelled = mutableListOf<Pair<String, Boolean>>()
    private val root by lazy { File(tmp, "scans").apply { mkdirs() } }
    private val repository by lazy {
        ScanRepositoryImpl(
            dao = dao,
            root = root,
            canceller = ProcessingCanceller { id -> cancelled += id to File(root, id).exists() },
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `create inserts a CAPTURED row and makes its folder`() = runTest {
        val scan = repository.create("Desk plant", createdAtMs = 1_000)

        assertEquals(ScanStatus.CAPTURED, scan.status)
        assertEquals(0, scan.splatCount)
        assertEquals(0, scan.keyframeCount)
        assertTrue(File(root, scan.folder).isDirectory)
        assertEquals(listOf(scan), repository.scans().first())
        assertEquals(scan, repository.get(scan.id))
    }

    @Test
    fun `scans are listed newest first`() = runTest {
        val old = repository.create("old", createdAtMs = 1_000)
        val new = repository.create("new", createdAtMs = 2_000)

        assertEquals(listOf(new.id, old.id), repository.scans().first().map { it.id })
    }

    @Test
    fun `status update sets the status and only the counts given`() = runTest {
        val scan = repository.create("s", createdAtMs = 1)

        repository.updateStatus(scan.id, ScanStatus.PROCESSING, keyframeCount = 42)
        repository.get(scan.id)!!.let {
            assertEquals(ScanStatus.PROCESSING, it.status)
            assertEquals(42, it.keyframeCount)
            assertEquals(0, it.splatCount)
        }

        repository.updateStatus(scan.id, ScanStatus.READY, splatCount = 1234)
        repository.get(scan.id)!!.let {
            assertEquals(ScanStatus.READY, it.status)
            assertEquals(42, it.keyframeCount)
            assertEquals(1234, it.splatCount)
        }
    }

    @Test
    fun `rename trims the name and ignores a blank one`() = runTest {
        val scan = repository.create("s", createdAtMs = 1)

        repository.rename(scan.id, "  Desk plant ")
        assertEquals("Desk plant", repository.get(scan.id)!!.name)

        repository.rename(scan.id, "   ")
        assertEquals("Desk plant", repository.get(scan.id)!!.name)
    }

    @Test
    fun `delete removes the folder and the row`() = runTest {
        val scan = repository.create("s", createdAtMs = 1)
        File(root, scan.folder).resolve("images").apply { mkdirs() }.resolve("frame_00001.jpg").writeBytes(ByteArray(8))

        repository.delete(scan.id)

        assertFalse(File(root, scan.folder).exists())
        assertNull(repository.get(scan.id))
        assertEquals(emptyList<Any>(), repository.scans().first())
        assertEquals(emptyList<Any>(), cancelled)
    }

    @Test
    fun `deleting a processing scan cancels processing before the folder goes`() = runTest {
        val scan = repository.create("s", createdAtMs = 1)
        repository.updateStatus(scan.id, ScanStatus.PROCESSING)

        repository.delete(scan.id)

        assertEquals(listOf(scan.id to true), cancelled)
        assertFalse(File(root, scan.folder).exists())
        assertNull(repository.get(scan.id))
    }

    @Test
    fun `delete refuses a folder outside the scans root and keeps the row`() = runTest {
        val outside = File(tmp, "outside").apply { mkdirs() }
        dao.insert(ScanEntity("evil", "evil", 1, ScanStatus.READY, 0, 0, "../outside"))

        val failure = runCatching { repository.delete("evil") }.exceptionOrNull()

        assertTrue(failure is IOException, "expected an IOException, got $failure")
        assertTrue(outside.isDirectory)
        assertEquals("evil", repository.get("evil")!!.id)
    }

    @Test
    fun `sync adopts a folder with a splat as READY with its counts`() = runTest {
        val folder = File(root, "found-ready").apply { mkdirs() }
        File(folder, "splat.ply").writeBytes(fixture())
        File(folder, "transforms.json").writeText("{}")
        File(folder, "images").mkdirs()
        repeat(3) { File(folder, "images/frame_0000$it.jpg").writeBytes(ByteArray(4)) }

        repository.sync()

        val scan = repository.get("found-ready")!!
        assertEquals(ScanStatus.READY, scan.status)
        assertEquals(4, scan.splatCount)
        assertEquals(3, scan.keyframeCount)
        assertEquals("found-ready", scan.folder)
        assertEquals(folder.lastModified(), scan.createdAtMs)
    }

    @Test
    fun `sync adopts a bundle without a splat as CAPTURED and a broken one as FAILED`() = runTest {
        File(root, "found-bundle").apply { mkdirs() }.resolve("transforms.json").writeText("{}")
        File(root, "found-broken").apply { mkdirs() }.resolve("splat.ply").writeText("not a ply")
        File(root, "found-empty").mkdirs()
        File(root, "stray.txt").writeText("not a scan")

        repository.sync()

        assertEquals(ScanStatus.CAPTURED, repository.get("found-bundle")!!.status)
        assertEquals(ScanStatus.FAILED, repository.get("found-broken")!!.status)
        assertEquals(ScanStatus.FAILED, repository.get("found-empty")!!.status)
        assertEquals(3, repository.scans().first().size)
    }

    @Test
    fun `sync adopts a pose-free capture as CAPTURED and keeps broken or bare ones FAILED`() = runTest {
        val ok = File(root, "pose-free").apply { mkdirs() }
        File(ok, "capture.json").writeText("{}")
        File(ok, "images").mkdirs()
        File(ok, "images/a.jpg").writeText("x")
        File(ok, "images/b.jpg").writeText("x")
        File(root, "pose-broken").apply { mkdirs() }.also {
            File(it, "capture.json").writeText("{}")
            File(it, "splat.ply").writeText("not a ply")
        }
        File(root, "only-images").apply { mkdirs() }.resolve("images").apply { mkdirs() }.resolve("a.jpg").writeText("x")

        repository.sync()

        val got = repository.get("pose-free")!!
        assertEquals(ScanStatus.CAPTURED, got.status)
        assertEquals(2, got.keyframeCount)
        assertEquals(false, got.imported)
        assertEquals(ScanStatus.FAILED, repository.get("pose-broken")!!.status)
        assertEquals(ScanStatus.FAILED, repository.get("only-images")!!.status)
    }

    @Test
    fun `sync keeps known rows and drops rows whose folder is gone`() = runTest {
        val kept = repository.create("kept", createdAtMs = 1)
        repository.rename(kept.id, "Desk plant")
        val gone = repository.create("gone", createdAtMs = 2)
        File(root, gone.folder).deleteRecursively()

        repository.sync()

        assertEquals(listOf(kept.id), repository.scans().first().map { it.id })
        assertEquals("Desk plant", repository.get(kept.id)!!.name)
    }

    @Test
    fun `refresh reads the folder again and keeps name and date`() = runTest {
        val scan = repository.create("s", createdAtMs = 7)
        repository.rename(scan.id, "Desk plant")
        repository.updateStatus(scan.id, ScanStatus.PROCESSING)
        val folder = File(root, scan.folder)
        File(folder, "transforms.json").writeText("{}")
        File(folder, "splat.ply").writeBytes(fixture())
        File(folder, "images").mkdirs()
        repeat(2) { File(folder, "images/frame_0000$it.jpg").writeBytes(ByteArray(4)) }

        repository.refresh(scan.id)

        repository.get(scan.id)!!.let {
            assertEquals(ScanStatus.READY, it.status)
            assertEquals(4, it.splatCount)
            assertEquals(2, it.keyframeCount)
            assertEquals("Desk plant", it.name)
            assertEquals(7, it.createdAtMs)
        }
    }

    @Test
    fun `refresh adopts a new folder and ignores a missing one`() = runTest {
        File(root, "fresh").apply { mkdirs() }.resolve("transforms.json").writeText("{}")

        repository.refresh("fresh")
        repository.refresh("nowhere")

        assertEquals(ScanStatus.CAPTURED, repository.get("fresh")!!.status)
        assertNull(repository.get("nowhere"))
    }

    @Test
    fun `a scan carries its folder size and its thumbnail, and an empty thumbnail counts as none`() = runTest {
        val scan = repository.create("s", createdAtMs = 1)
        val folder = File(root, scan.folder)
        File(folder, "images").mkdirs()
        File(folder, "images/frame_00000.jpg").writeBytes(ByteArray(1_000))
        val picture = File(folder, "thumbnail.png").apply { writeBytes(ByteArray(24)) }

        repository.scans().first().single().let {
            assertEquals(1_024L, it.sizeBytes)
            assertEquals(picture.path, it.thumbnail?.path)
            assertEquals(picture.lastModified(), it.thumbnail?.stamp)
        }

        picture.writeBytes(ByteArray(0))
        repository.get(scan.id)!!.let {
            assertNull(it.thumbnail, "a 0-byte thumbnail.png shows the placeholder")
            assertEquals(1_000L, it.sizeBytes)
        }
    }

    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/splats/tiny-sh1.ply")!!.use { it.readBytes() }
}

/** In-memory [ScanDao] with Room's semantics: insert ignores an existing id, observeAll is newest first. */
internal class FakeScanDao : ScanDao {
    private val rows = MutableStateFlow<Map<String, ScanEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ScanEntity>> =
        rows.map { it.values.sortedByDescending(ScanEntity::createdAtMs) }

    override suspend fun all(): List<ScanEntity> = rows.value.values.toList()

    override suspend fun byId(id: String): ScanEntity? = rows.value[id]

    override suspend fun insert(scan: ScanEntity): Long {
        if (scan.id in rows.value) return -1
        rows.value = rows.value + (scan.id to scan)
        return rows.value.size.toLong()
    }

    override suspend fun update(scan: ScanEntity) {
        if (scan.id in rows.value) rows.value = rows.value + (scan.id to scan)
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }
}
