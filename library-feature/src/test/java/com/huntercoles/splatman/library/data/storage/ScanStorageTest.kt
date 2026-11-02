package com.huntercoles.splatman.library.data.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ScanStorageTest {

    @TempDir
    lateinit var root: File

    private fun file(path: String, bytes: Int) = File(root, path).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test
    fun `an empty or missing folder measures zero`() {
        assertEquals(StorageSummary(0, 0, 0), ScanStorage(root).measure())
        assertEquals(StorageSummary(0, 0, 0), ScanStorage(File(root, "absent")).measure())
    }

    @Test
    fun `measure counts every scan and only the frames of built scans as clearable`() {
        file("built/splat.ply", 100)
        file("built/transforms.json", 10)
        file("built/images/0001.jpg", 1_000)
        file("built/images/0002.jpg", 1_000)
        file("waiting/transforms.json", 10)
        file("waiting/images/0001.jpg", 500)
        file("stray.txt", 7)

        assertEquals(StorageSummary(usedBytes = 2_620, scanCount = 2, clearableBytes = 2_000), ScanStorage(root).measure())
    }

    @Test
    fun `clear frames frees built scans' frames and keeps the scans and unbuilt frames`() {
        file("built/splat.ply", 100)
        file("built/images/0001.jpg", 1_000)
        file("waiting/images/0001.jpg", 500)
        val storage = ScanStorage(root)

        assertEquals(1_000, storage.clearFrames())

        assertFalse(File(root, "built/images").exists())
        assertTrue(File(root, "built/splat.ply").isFile)
        assertTrue(File(root, "waiting/images/0001.jpg").isFile)
        assertEquals(StorageSummary(usedBytes = 600, scanCount = 2, clearableBytes = 0), storage.measure())
        assertEquals(0, storage.clearFrames())
    }
}
