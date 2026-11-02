package com.huntercoles.splatman.library.domain.model

import com.huntercoles.splatman.library.data.sample.AssetSampleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryModelTest {

    private fun scan(status: ScanStatus) = Scan("id1", "n", 0, status, 0, 0, "id1")

    @Test
    fun `only READY scans open in the viewer`() {
        assertEquals(OpenTarget.Viewer("id1"), scan(ScanStatus.READY).openTarget())
        assertEquals(OpenTarget.Processing("id1"), scan(ScanStatus.CAPTURED).openTarget())
        assertEquals(OpenTarget.Processing("id1"), scan(ScanStatus.PROCESSING).openTarget())
        assertEquals(OpenTarget.Processing("id1"), scan(ScanStatus.FAILED).openTarget())
    }

    @Test
    fun `a sample is named from its file and identified by a sample id`() {
        assertEquals(
            Sample("sample:measuring-tape.ply", "Measuring tape", "measuring-tape.ply"),
            Sample.fromFileName("measuring-tape.ply"),
        )
        assertEquals("Cactus", Sample.fromFileName("cactus.spz").name)
    }

    @Test
    fun `sample files skip the licence notes and are sorted`() {
        assertEquals(
            listOf("cactus-sh1.ply", "cactus.spz", "measuring-tape.ply"),
            AssetSampleRepository.sampleFiles(
                listOf("measuring-tape.ply", "ASSETS-LICENSES.md", "cactus.spz", "cactus-sh1.ply"),
            ),
        )
    }
}
