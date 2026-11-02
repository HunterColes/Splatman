package com.huntercoles.splatman.splat.io.ply

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/** The bundled samples of #5 (app/src/main/assets/samples/ASSETS-LICENSES.md) with their documented count and degree. */
class PlySamplesTest {

    private fun samplesDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(requireNotNull(dir) { "repo root not found" }, "app/src/main/assets/samples")
    }

    @ParameterizedTest
    @CsvSource("cactus-sh1.ply, 139410, 1", "measuring-tape.ply, 19469, 0")
    fun `reads each sample with its documented count and degree`(name: String, count: Int, degree: Int) {
        val result = File(samplesDir(), name).inputStream().use { PlyReader.readWithReport(it) }
        assertEquals(count, result.cloud.count)
        assertEquals(degree, result.cloud.shDegree)
        assertEquals(degree, result.report.fileShDegree)
        assertTrue(result.cloud.positions.all { it.isFinite() })
    }
}
