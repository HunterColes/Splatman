package com.huntercoles.splatman.library.importing

import com.huntercoles.splatman.library.data.repository.FakeScanDao
import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import com.huntercoles.splatman.library.domain.model.ScanStatus
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.openTarget
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.splat.io.ply.PlyReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** [SplatImporter] over the real repository (fake DAO, real folders). */
class SplatImporterTest {

    @TempDir
    lateinit var tmp: File

    private val dao = FakeScanDao()
    private val root by lazy { File(tmp, "scans").apply { mkdirs() } }
    private val repository by lazy { ScanRepositoryImpl(dao, root, ProcessingCanceller { }, Dispatchers.Unconfined) }
    private var free = Long.MAX_VALUE

    private fun importer() = SplatImporter(
        validator = ImportValidator(maxSplats = 1_000),
        scans = repository,
        freeBytes = { free },
        io = Dispatchers.Unconfined,
        now = { 42L },
    )

    private fun source(name: String, bytes: ByteArray) = object : ImportSource {
        override val fileName = name
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test
    fun `a valid splat becomes an imported READY scan whose splat ply the viewer reads`() = runTest {
        val outcome = importer().import(source("garden.splat", ImportFixtures.splat(ImportFixtures.cloud(6))))

        val scan = assertInstanceOf(ImportOutcome.Imported::class.java, outcome).scan
        assertEquals("garden", scan.name)
        assertEquals(ScanStatus.READY, scan.status)
        assertEquals(6, scan.splatCount)
        assertEquals(42L, scan.createdAtMs)
        assertTrue(scan.imported)
        assertEquals(OpenTarget.Viewer(scan.id), scan.openTarget())
        assertEquals(listOf(scan), repository.scans().first())
        val ply = File(File(root, scan.folder), ScanRepositoryImpl.SPLAT_FILE)
        assertEquals(6, ply.inputStream().use { PlyReader.read(it) }.count)
        assertEquals(SplatImporter.plyBytes(6, 0), ply.length())
        assertFalse(File(File(root, scan.folder), "${ScanRepositoryImpl.SPLAT_FILE}.tmp").exists())
    }

    @Test
    fun `an imported folder is adopted as imported again after the rows are lost`() = runTest {
        val outcome = importer().import(source("desk.ply", ImportFixtures.ply(ImportFixtures.cloud(3, shDegree = 1))))
        val scan = (outcome as ImportOutcome.Imported).scan
        dao.delete(scan.id)

        repository.sync()

        val adopted = repository.get(scan.id)!!
        assertTrue(adopted.imported)
        assertEquals(3, adopted.splatCount)
    }

    @Test
    fun `not enough space refuses the file with what is needed and free, and makes no scan`() = runTest {
        free = 100
        val outcome = importer().import(source("desk.ply", ImportFixtures.ply(ImportFixtures.cloud(3))))

        val refused = assertInstanceOf(ImportOutcome.Refused::class.java, outcome)
        assertEquals(ImportValidation.NoSpace(SplatImporter.plyBytes(3, 0), 100), refused.validation)
        assertTrue(repository.scans().first().isEmpty())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an invalid file is refused and makes no scan`() = runTest {
        val outcome = importer().import(source("cloud.ply", ImportFixtures.pointCloudPly(4)))

        assertEquals(ImportOutcome.Refused(ImportValidation.PointCloud), outcome)
        assertTrue(repository.scans().first().isEmpty())
    }

    @Test
    fun `a failed write removes the half-made folder`() = runTest {
        val thrown = runCatching {
            repository.import("x", 1, 1) { error("disk gone") }
        }.exceptionOrNull()

        assertEquals("disk gone", thrown?.message)
        assertTrue(root.listFiles().isNullOrEmpty())
        assertTrue(repository.scans().first().isEmpty())
    }
}
