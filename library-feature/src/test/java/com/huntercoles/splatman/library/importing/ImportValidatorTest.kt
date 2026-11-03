package com.huntercoles.splatman.library.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ImportValidatorTest {

    private val validator = ImportValidator(maxSplats = 100)

    private fun validate(name: String, bytes: ByteArray) =
        validator.validate(name) { ByteArrayInputStream(bytes) }

    @Test
    fun `a 3DGS PLY is valid and named after the file`() {
        val result = validate("Desk plant.ply", ImportFixtures.ply(ImportFixtures.cloud(5, shDegree = 1)))

        val valid = assertInstanceOf(ImportValidation.Valid::class.java, result)
        assertEquals("Desk plant", valid.name)
        assertEquals(5, valid.cloud.count)
        assertEquals(1, valid.cloud.shDegree)
    }

    @Test
    fun `a splat file is valid by content`() {
        val result = validate("garden.splat", ImportFixtures.splat(ImportFixtures.cloud(4)))

        val valid = assertInstanceOf(ImportValidation.Valid::class.java, result)
        assertEquals("garden", valid.name)
        assertEquals(4, valid.cloud.count)
    }

    @Test
    fun `a PLY with positions and colours but no splat properties is a point cloud`() {
        assertEquals(ImportValidation.PointCloud, validate("scan.ply", ImportFixtures.pointCloudPly(3)))
    }

    @Test
    fun `a text file is unsupported and named by its extension`() {
        val obj = "v 0 0 0\nv 1 0 0\nf 1 2 1\n".toByteArray()
        assertEquals(ImportValidation.Unsupported(".obj"), validate("Mesh.OBJ", obj))
    }

    @Test
    fun `a file with no extension is named whole`() {
        assertEquals(ImportValidation.Unsupported("notes"), validate("notes", "hello".toByteArray()))
    }

    @Test
    fun `an empty file is unsupported`() {
        assertEquals(ImportValidation.Unsupported(".ply"), validate("empty.ply", ByteArray(0)))
    }

    @Test
    fun `a PLY whose header never ends is unsupported`() {
        val broken = "ply\nformat binary_little_endian 1.0\nelement vertex 3\n".toByteArray()
        assertEquals(ImportValidation.Unsupported(".ply"), validate("broken.ply", broken))
    }

    @Test
    fun `a truncated PLY body is corrupt`() {
        val ply = ImportFixtures.ply(ImportFixtures.cloud(5))
        assertEquals(ImportValidation.Corrupt, validate("cut.ply", ply.copyOf(ply.size - 10)))
    }

    @Test
    fun `a PLY holding a NaN is corrupt`() {
        val ply = ImportFixtures.withNaN(ImportFixtures.ply(ImportFixtures.cloud(5)))
        assertEquals(ImportValidation.Corrupt, validate("nan.ply", ply))
    }

    @Test
    fun `a PLY with no splats is corrupt`() {
        assertEquals(ImportValidation.Corrupt, validate("none.ply", ImportFixtures.ply(ImportFixtures.cloud(0))))
    }

    @Test
    fun `a splat file cut mid-record is corrupt`() {
        val splat = ImportFixtures.splat(ImportFixtures.cloud(4))
        assertEquals(ImportValidation.Corrupt, validate("cut.splat", splat.copyOf(splat.size - 5)))
    }

    @Test
    fun `whole records of garbage are corrupt when named splat, unsupported otherwise`() {
        val nan = ByteArray(64) { 0xFF.toByte() }
        assertEquals(ImportValidation.Corrupt, validate("junk.splat", nan))
        assertEquals(ImportValidation.Unsupported(".bin"), validate("junk.bin", nan))
    }

    @Test
    fun `a PLY over the budget is too large, before its body is read`() {
        val ply = ImportFixtures.ply(ImportFixtures.cloud(101))
        assertEquals(ImportValidation.TooLarge(101), validate("big.ply", ply.copyOf(ply.size / 2)))
    }

    @Test
    fun `a splat file over the budget is too large`() {
        assertEquals(
            ImportValidation.TooLarge(150),
            validate("big.splat", ImportFixtures.splat(ImportFixtures.cloud(150))),
        )
    }

    @Test
    fun `an SPZ is unsupported for now`() {
        val gzipSpz = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write("NGSP".toByteArray() + ByteArray(12)) }
        }.toByteArray()
        assertEquals(ImportValidation.Unsupported(".spz"), validate("cactus.spz", gzipSpz))
    }

    @Test
    fun `names keep everything before the last dot`() {
        assertEquals("my.scan", ImportValidator.nameOf("my.scan.ply"))
        assertEquals(".ply", ImportValidator.nameOf(".ply"))
    }
}
