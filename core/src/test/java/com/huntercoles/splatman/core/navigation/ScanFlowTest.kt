package com.huntercoles.splatman.core.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScanFlowTest {

    @Test
    fun `a saved scan replaces Capture with its Processing route`() {
        val step = ScanFlow.afterCapture("scan_1")
        assertEquals(NavigationDestination.Processing("scan_1"), step.destination)
        assertEquals(NavigationDestination.Capture, step.replaces)
        assertEquals("processing/scan_1", ShellRoutes.path(step.destination))
    }

    @Test
    fun `a ready splat replaces Processing with the Viewer of the same scan`() {
        val step = ScanFlow.afterProcessing("scan_1")
        assertEquals(NavigationDestination.Viewer("scan_1"), step.destination)
        assertEquals(NavigationDestination.Processing("scan_1"), step.replaces)
        assertEquals("viewer/scan_1", ShellRoutes.path(step.destination))
    }
}
