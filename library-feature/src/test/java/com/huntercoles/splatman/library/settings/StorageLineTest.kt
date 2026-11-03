package com.huntercoles.splatman.library.settings

import com.huntercoles.splatman.library.data.storage.StorageSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StorageLineTest {

    @Test
    fun `no summary yet reads as counting`() {
        assertEquals(StorageLine.Counting, storageLine(null))
    }

    @Test
    fun `zero scans reads as empty, not a plural`() {
        assertEquals(StorageLine.Empty, storageLine(StorageSummary(usedBytes = 4_096, scanCount = 0, clearableBytes = 0)))
    }

    @Test
    fun `one scan keeps its count for the singular form`() {
        assertEquals(StorageLine.Used(1_000, 1), storageLine(StorageSummary(1_000, 1, 0)))
    }

    @Test
    fun `many scans keep size and count`() {
        assertEquals(StorageLine.Used(340_000_000, 3), storageLine(StorageSummary(340_000_000, 3, 10)))
    }
}
