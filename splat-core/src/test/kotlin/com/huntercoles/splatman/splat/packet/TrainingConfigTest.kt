package com.huntercoles.splatman.splat.packet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrainingConfigTest {

    @Test
    fun `defaults are ADR 0005`() {
        val c = TrainingConfig()
        assertEquals(3000, c.iterations)
        assertEquals(100_000, c.maxSplats)
        assertEquals(0, c.shDegree)
        assertEquals(320, c.imageLongSide)
    }

    @Test
    fun `rejects out-of-range values`() {
        assertThrows<IllegalArgumentException> { TrainingConfig(iterations = -1) }
        assertThrows<IllegalArgumentException> { TrainingConfig(maxSplats = 0) }
        assertThrows<IllegalArgumentException> { TrainingConfig(shDegree = 4) }
        assertThrows<IllegalArgumentException> { TrainingConfig(imageLongSide = 0) }
    }

    @Test
    fun `every packet carries a version`() {
        assertEquals(1, TrainingConfig.VERSION)
        assertEquals(1, TrainingInput.VERSION)
        assertEquals(1, TrainingProgress.VERSION)
        assertEquals(1, TrainingResult.VERSION)
        assertEquals(1, ExportFormat.VERSION)
        assertEquals(listOf("PLY", "SPLAT", "SPZ"), ExportFormat.entries.map { it.name })
    }
}
