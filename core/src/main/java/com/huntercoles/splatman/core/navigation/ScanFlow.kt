package com.huntercoles.splatman.core.navigation

/**
 * The scan flow's hand-offs: Capture -> Processing -> Viewer. Each step replaces the screen it leaves, so Back from
 * Processing or the Viewer lands in the Library, never on a finished capture or a finished build.
 */
object ScanFlow {

    /** Go to [destination], taking [replaces] (and anything above it) off the back stack first. */
    data class Step(val destination: NavigationDestination, val replaces: NavigationDestination)

    /** A scan was saved: build its splat. */
    fun afterCapture(scanId: String): Step =
        Step(NavigationDestination.Processing(scanId), replaces = NavigationDestination.Capture)

    /** The splat is ready: show it. */
    fun afterProcessing(scanId: String): Step =
        Step(NavigationDestination.Viewer(scanId), replaces = NavigationDestination.Processing(scanId))
}
