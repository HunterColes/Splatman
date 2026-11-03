package com.huntercoles.splatman.viewer.crop.store

import com.huntercoles.splatman.splat.io.ply.PlyReader
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** [FileCropStore] on a temp `filesDir`: ADR 0011 rule 6 order, every crash point, samples and the listener. */
class FileCropStoreTest {

    @TempDir
    lateinit var filesDir: File

    private val changed = mutableListOf<String>()
    private val listener = ScanChangeListener { changed += it }
    private val scan = SplatSource.Scan("scan-1")
    private val sample = SplatSource.Sample("samples/cactus-sh1.ply", "Cactus")
    private lateinit var folder: File
    private val splat get() = File(folder, SplatSource.Scan.SPLAT_FILE)
    private val original get() = File(folder, CropPaths.UNCROPPED_FILE)

    @BeforeEach
    fun setUp() {
        folder = File(File(filesDir, CropPaths.SCANS_DIR), scan.scanId).apply { mkdirs() }
        DiskCropFileOps.write(splat, cloud(OLD))
    }

    private fun store(ops: CropFileOps = DiskCropFileOps) = FileCropStore(filesDir, listener, Dispatchers.Unconfined, ops)

    @Test
    fun `first Apply writes the crop and keeps the original`() = runTest {
        val store = store()
        assertFalse(store.hasOriginal(scan))

        store.apply(scan, cloud(NEW))

        assertEquals(NEW, countOf(splat))
        assertEquals(OLD, countOf(original))
        assertTrue(store.hasOriginal(scan))
        assertEquals(listOf(SPLAT, UNCROPPED), names())
        assertEquals(listOf(scan.scanId), changed)
    }

    @Test
    fun `second Apply crops the current splat and leaves the first original`() = runTest {
        val store = store()
        store.apply(scan, cloud(NEW))
        store.apply(scan, cloud(NEWER))

        assertEquals(NEWER, countOf(splat))
        assertEquals(OLD, countOf(original))
        assertEquals(listOf(scan.scanId, scan.scanId), changed)
    }

    @Test
    fun `Apply follows the three-step order`() = runTest {
        val calls = mutableListOf<String>()
        val recording = object : CropFileOps by DiskCropFileOps {
            override fun write(file: File, cloud: SplatCloud) {
                calls += "write ${file.name}"
                DiskCropFileOps.write(file, cloud)
            }
            override fun copy(from: File, to: File) {
                calls += "copy ${from.name} ${to.name}"
                DiskCropFileOps.copy(from, to)
            }
            override fun rename(from: File, to: File) {
                calls += "rename ${from.name} ${to.name}"
                DiskCropFileOps.rename(from, to)
            }
        }
        store(recording).apply(scan, cloud(NEW))
        store(recording).apply(scan, cloud(NEWER))

        assertEquals(
            listOf(
                "write splat.ply.tmp",
                "copy splat.ply splat.uncropped.ply.tmp",
                "rename splat.uncropped.ply.tmp splat.uncropped.ply",
                "rename splat.ply.tmp splat.ply",
                "write splat.ply.tmp",
                "rename splat.ply.tmp splat.ply",
            ),
            calls,
        )
    }

    @Test
    fun `Revert restores the original and removes it`() = runTest {
        val store = store()
        store.apply(scan, cloud(NEW))
        store.apply(scan, cloud(NEWER))
        changed.clear()

        store.revert(scan)

        assertEquals(OLD, countOf(splat))
        assertFalse(original.exists())
        assertFalse(store.hasOriginal(scan))
        assertEquals(listOf(SPLAT), names())
        assertEquals(listOf(scan.scanId), changed)
    }

    @Test
    fun `Revert with no original is a no-op`() = runTest {
        store().revert(scan)

        assertEquals(OLD, countOf(splat))
        assertEquals(listOf(SPLAT), names())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `stale tmp files are deleted at the next Apply`() = runTest {
        File(folder, "$SPLAT.tmp").writeText("half a file")
        File(folder, "$UNCROPPED.tmp").writeText("half a copy")

        store().apply(scan, cloud(NEW))

        assertEquals(listOf(SPLAT, UNCROPPED), names())
        assertEquals(OLD, countOf(original))
    }

    @Test
    fun `a crash after step 1 leaves the old splat and a stale tmp the next Apply clears`() = runTest {
        // A process death leaves no chance to clean up: only the tmp from step 1 is on disk.
        DiskCropFileOps.write(File(folder, "$SPLAT.tmp"), cloud(NEW))
        assertEquals(OLD, countOf(splat))

        store().apply(scan, cloud(NEWER))
        assertEquals(NEWER, countOf(splat))
        assertEquals(OLD, countOf(original))
    }

    @Test
    fun `disk full during step 1 keeps the old splat and deletes the tmp`() = runTest {
        val full = failing(write = true)
        assertThrows<IOException> { store(full).apply(scan, cloud(NEW)) }

        assertOld()
    }

    @Test
    fun `disk full during the step 2 copy keeps the old splat and deletes both tmps`() = runTest {
        assertThrows<IOException> { store(failing(copy = true)).apply(scan, cloud(NEW)) }

        assertOld()
    }

    @Test
    fun `a failed step 2 rename keeps the old splat and deletes both tmps`() = runTest {
        assertThrows<IOException> { store(failing(renameTo = UNCROPPED)).apply(scan, cloud(NEW)) }

        assertOld()
    }

    @Test
    fun `a failed step 3 rename keeps the old splat, with the original kept`() = runTest {
        assertThrows<IOException> { store(failing(renameTo = SPLAT)).apply(scan, cloud(NEW)) }

        assertEquals(OLD, countOf(splat))
        assertEquals(OLD, countOf(original))
        assertEquals(listOf(SPLAT, UNCROPPED), names())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `a cancel before step 3 keeps the old splat`() = runTest {
        lateinit var job: Job
        val cancelling = object : CropFileOps by DiskCropFileOps {
            override fun write(file: File, cloud: SplatCloud) {
                DiskCropFileOps.write(file, cloud)
                job.cancel()
            }
        }
        job = launch { store(cancelling).apply(scan, cloud(NEW)) }
        job.join()

        assertTrue(job.isCancelled)
        assertOld()
    }

    @Test
    fun `a sample crop is written beside the assets and Revert deletes it`() = runTest {
        val store = store()
        val crop = File(File(filesDir, CropPaths.SAMPLE_CROPS_DIR), "cactus-sh1.ply")
        assertFalse(store.hasOriginal(sample))

        store.apply(sample, cloud(NEW))

        assertEquals(NEW, countOf(crop))
        assertTrue(store.hasOriginal(sample))
        assertEquals(listOf("cactus-sh1.ply"), crop.parentFile!!.list()!!.sorted())

        store.revert(sample)

        assertFalse(crop.exists())
        assertFalse(store.hasOriginal(sample))
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `a failed sample write leaves no crop and no tmp`() = runTest {
        assertThrows<IOException> { store(failing(write = true)).apply(sample, cloud(NEW)) }

        assertTrue(File(filesDir, CropPaths.SAMPLE_CROPS_DIR).list()!!.isEmpty())
    }

    @Test
    fun `a picked document is refused`() = runTest {
        val doc = SplatSource.Document("content://x/y")
        val store = store()
        assertThrows<IllegalArgumentException> { store.apply(doc, cloud(NEW)) }
        assertThrows<IllegalArgumentException> { store.revert(doc) }
        assertThrows<IllegalArgumentException> { store.hasOriginal(doc) }
    }

    @Test
    fun `no scan id or sample name escapes its folder`() = runTest {
        val store = store()
        for (id in listOf("..", "../scan-1", "a/b", "a\\b", ".hidden", " scan-1", "")) {
            assertThrows<IllegalArgumentException>(id) { store.apply(SplatSource.Scan(id), cloud(NEW)) }
            assertThrows<IllegalArgumentException>(id) { store.revert(SplatSource.Scan(id)) }
        }
        for (path in listOf("samples/..", "samples/", "samples/.x.ply", "samples/a\\b.ply")) {
            assertThrows<IllegalArgumentException>(path) { store.apply(SplatSource.Sample(path, "x"), cloud(NEW)) }
        }
        assertEquals(OLD, countOf(splat))
        assertFalse(File(filesDir, CropPaths.SAMPLE_CROPS_DIR).exists())
    }

    private suspend fun assertOld() {
        assertEquals(OLD, countOf(splat))
        assertEquals(listOf(SPLAT), names())
        assertFalse(store().hasOriginal(scan))
        assertTrue(changed.isEmpty())
    }

    /** Disk ops that write part of a file and then fail, as a full disk would. */
    private fun failing(write: Boolean = false, copy: Boolean = false, renameTo: String? = null) =
        object : CropFileOps {
            override fun write(file: File, cloud: SplatCloud) {
                if (write) {
                    file.writeText("ply\n")
                    throw IOException("No space left on device")
                }
                DiskCropFileOps.write(file, cloud)
            }

            override fun copy(from: File, to: File) {
                if (copy) {
                    to.writeText("ply\n")
                    throw IOException("No space left on device")
                }
                DiskCropFileOps.copy(from, to)
            }

            override fun rename(from: File, to: File) {
                if (to.name == renameTo) throw IOException("rename failed")
                DiskCropFileOps.rename(from, to)
            }
        }

    private fun names() = folder.list()!!.sorted()

    private fun countOf(file: File) = file.inputStream().use { PlyReader.read(it) }.count

    private companion object {
        const val SPLAT = "splat.ply"
        const val UNCROPPED = "splat.uncropped.ply"
        const val OLD = 10
        const val NEW = 4
        const val NEWER = 2

        fun cloud(n: Int) = SplatCloud(
            count = n,
            shDegree = 0,
            positions = FloatArray(3 * n) { it * 0.1f },
            logScales = FloatArray(3 * n) { -3f },
            rotations = FloatArray(4 * n) { if (it % 4 == 0) 1f else 0f },
            opacityLogits = FloatArray(n),
            shDc = FloatArray(3 * n),
            shRest = FloatArray(0),
        )
    }
}
