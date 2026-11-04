package com.huntercoles.splatman.library.data.repository

import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.local.ScanEntity
import com.huntercoles.splatman.library.domain.DefaultScanName
import com.huntercoles.splatman.library.domain.LandingFolder
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
import java.time.ZoneId
import java.util.Locale

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
    fun `sync adopts a bundle without a splat as CAPTURED, a broken one as FAILED, and hides a bare folder`() = runTest {
        File(root, "found-bundle").apply { mkdirs() }.resolve("transforms.json").writeText("{}")
        File(root, "found-broken").apply { mkdirs() }.resolve("splat.ply").writeText("not a ply")
        File(root, "found-empty").mkdirs()
        File(root, "stray.txt").writeText("not a scan")

        repository.sync()

        assertEquals(ScanStatus.CAPTURED, repository.get("found-bundle")!!.status)
        assertEquals(ScanStatus.FAILED, repository.get("found-broken")!!.status)
        assertNull(repository.get("found-empty"))
        assertEquals(2, repository.scans().first().size)
    }

    @Test
    fun `sync adopts a pose-free capture as CAPTURED, a broken one FAILED, and hides one without capture json`() = runTest {
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
        assertNull(repository.get("only-images"))
    }

    @Test
    fun `Hunter's Pixel scan, capture json written last and no transforms json, lists as CAPTURED on a fresh start`() =
        runTest {
            pixelScan(root, PIXEL_SCAN, frames = 16)

            // A fresh start: a new repository over the same (empty) table, and the Library's first look.
            val fresh = ScanRepositoryImpl(FakeScanDao(), root, ProcessingCanceller { }, Dispatchers.Unconfined)
            val listed = fresh.scans().first().single()

            assertEquals(PIXEL_SCAN, listed.id)
            assertEquals(ScanStatus.CAPTURED, listed.status)
            assertEquals(16, listed.keyframeCount)
            assertFalse(listed.imported)
        }

    @Test
    fun `a scan Stop renames in while the Library is open lists on the Library's next look`() = runTest {
        assertEquals(emptyList<Any>(), repository.scans().first(), "the Library is open with no scans")
        val staging = File(tmp, "recording").apply { mkdirs() }
        val take = pixelScan(staging, PIXEL_SCAN, frames = 33)

        // FossPoseFreeRecorder.stop: the finished folder is renamed into the scans root; nothing writes a row.
        assertTrue(take.renameTo(File(root, PIXEL_SCAN)))

        repository.scans().first().single().let {
            assertEquals(PIXEL_SCAN, it.id)
            assertEquals(ScanStatus.CAPTURED, it.status)
            assertEquals(33, it.keyframeCount)
        }
    }

    @Test
    fun `a half-written folder stays hidden until its capture json lands`() = runTest {
        val half = File(root, PIXEL_SCAN).apply { mkdirs() }
        File(half, "images").mkdirs()
        File(half, "images/frame_00001.jpg").writeBytes(ByteArray(8))
        File(half, "imu.csv").writeText("timestamp_ns,sensor,x,y,z\n")

        assertEquals(emptyList<Any>(), repository.scans().first())
        assertNull(repository.get(PIXEL_SCAN))

        File(half, "capture.json").writeText(captureJson(1))
        assertEquals(ScanStatus.CAPTURED, repository.scans().first().single().status)
    }

    @Test
    fun `a capture json that does not parse leaves the Library listing every scan`() = runTest {
        File(root, "garbled").apply { mkdirs() }.resolve("capture.json").writeText("{\"poses\": \"none\", \"frames\": [")
        pixelScan(root, PIXEL_SCAN, frames = 2)

        val listed = repository.scans().first()

        assertEquals(setOf("garbled", PIXEL_SCAN), listed.map { it.id }.toSet())
        assertEquals(ScanStatus.CAPTURED, repository.get(PIXEL_SCAN)!!.status)
    }

    @Test
    fun `a folder with both capture json and transforms json gives one row`() = runTest {
        pixelScan(root, PIXEL_SCAN, frames = 3).resolve("transforms.json").writeText("{}")

        repository.sync()
        val listed = repository.scans().first()

        assertEquals(listOf(PIXEL_SCAN), listed.map { it.id })
        assertEquals(ScanStatus.CAPTURED, listed.single().status)
    }

    /** The repository with en-GB's 24-hour default namer (#426). */
    private val named by lazy {
        ScanRepositoryImpl(
            dao = dao,
            root = root,
            canceller = ProcessingCanceller { },
            io = Dispatchers.Unconfined,
            namer = DefaultScanName("Scan %1\$s", "d MMM", "HH:mm", Locale.UK, ZoneId.of("Europe/London")),
        )
    }

    @Test
    fun `426 a synced capture folder lists under its default name`() = runTest {
        pixelScan(root, "scan_20261104_073012", frames = 2)

        named.sync()

        assertEquals(listOf("Scan 4 Nov, 07:30"), named.scans().first().map { it.name })
        assertEquals("scan_20261104_073012", named.get("scan_20261104_073012")!!.folder)
    }

    @Test
    fun `426 a refreshed capture without a row gets the default name and keeps a rename`() = runTest {
        pixelScan(root, "scan_20261104_073012", frames = 2)

        named.refresh("scan_20261104_073012")
        assertEquals("Scan 4 Nov, 07:30", named.get("scan_20261104_073012")!!.name)

        named.rename("scan_20261104_073012", "Desk plant")
        named.sync()
        named.refresh("scan_20261104_073012")
        assertEquals("Desk plant", named.get("scan_20261104_073012")!!.name)
    }

    @Test
    fun `426 an adopted imported folder keeps its folder name`() = runTest {
        File(root, "imported_one").apply { mkdirs() }.resolve("splat.ply").writeBytes(fixture())

        named.sync()

        named.get("imported_one")!!.let {
            assertTrue(it.imported)
            assertEquals("imported_one", it.name)
        }
    }

    @Test
    fun `426 rows that already exist keep their names`() = runTest {
        pixelScan(root, PIXEL_SCAN, frames = 1)
        dao.insert(ScanEntity(PIXEL_SCAN, PIXEL_SCAN, 1, ScanStatus.CAPTURED, 0, 1, PIXEL_SCAN))

        named.sync()

        assertEquals(PIXEL_SCAN, named.get(PIXEL_SCAN)!!.name)
    }

    /** #334's scan_20261103_132504 layout under [parent]: images, `imu.csv`, `calibration.json`, `capture.json` last. */
    private fun pixelScan(parent: File, name: String, frames: Int): File {
        val folder = File(parent, name).apply { mkdirs() }
        File(folder, "images").mkdirs()
        repeat(frames) { File(folder, "images/frame_%05d.jpg".format(it + 1)).writeBytes(ByteArray(16)) }
        File(folder, "imu.csv").writeText("timestamp_ns,sensor,x,y,z\n1,gyro,0,0,0\n")
        File(folder, "calibration.json").writeText("{\"image\": {\"w\": 1920, \"h\": 1080}}")
        File(folder, "capture.json").writeText(captureJson(frames))
        return folder
    }

    /** `capture.json` in the shape PoseFreeCaptureWriter writes on the Pixel: `"poses": "none"`, one entry a frame. */
    private fun captureJson(frames: Int): String {
        val entries = (1..frames).joinToString(",\n") { i ->
            ("    {\"file_path\": \"images/frame_%05d.jpg\", \"timestamp_ns\": %d, \"w\": 1920, \"h\": 1080, " +
                "\"sharpness\": 41.5, \"fl_x\": 1450.2, \"fl_y\": 1450.2, \"cx\": 960.0, \"cy\": 540.0, " +
                "\"exposure_ns\": 8000000}").format(i, FIRST_NS + i * FRAME_NS)
        }
        return "{\n  \"splatman_capture_version\": 1,\n  \"poses\": \"none\",\n  \"imu_file_path\": \"imu.csv\",\n" +
            "  \"calibration_file_path\": \"calibration.json\",\n  \"timestamp_source\": \"REALTIME\",\n" +
            "  \"frames\": [\n$entries\n  ]\n}\n"
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

    @Test
    fun `create, import and refresh adoption land in the open folder while it exists`() = runTest {
        val landing = LandingFolder().apply { set("plants") }
        val folders = mutableSetOf("plants")
        val landed = ScanRepositoryImpl(dao, root, ProcessingCanceller { }, Dispatchers.Unconfined, landing) {
            it in folders
        }

        val made = landed.create("made", createdAtMs = 1)
        val imported = landed.import("imported", 2, 4) { it.write(fixture()) }
        File(root, "fresh").apply { mkdirs() }.resolve("transforms.json").writeText("{}")
        landed.refresh("fresh")

        assertEquals("plants", made.folderId)
        assertEquals("plants", landed.get(imported.id)!!.folderId)
        assertEquals("plants", landed.get("fresh")!!.folderId)

        folders.clear()
        assertNull(landed.create("stale", createdAtMs = 3).folderId, "a deleted folder lands at the root")
        landing.set(null)
        folders += "plants"
        assertNull(landed.create("root", createdAtMs = 4).folderId)
    }

    @Test
    fun `refresh keeps a known row's folder and sync adopts at the root`() = runTest {
        val landing = LandingFolder()
        val landed = ScanRepositoryImpl(dao, root, ProcessingCanceller { }, Dispatchers.Unconfined, landing) { true }
        val scan = landed.create("s", createdAtMs = 1)
        landing.set("plants")
        File(root, "found").apply { mkdirs() }.resolve("transforms.json").writeText("{}")

        landed.sync()
        landed.refresh(scan.id)

        assertNull(landed.get("found")!!.folderId)
        assertNull(landed.get(scan.id)!!.folderId)
    }

    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/splats/tiny-sh1.ply")!!.use { it.readBytes() }

    private companion object {
        const val PIXEL_SCAN = "scan_20261103_132504"
        const val FIRST_NS = 1_000_000_000L
        const val FRAME_NS = 250_000_000L
    }
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
