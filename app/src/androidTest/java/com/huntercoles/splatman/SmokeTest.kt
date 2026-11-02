package com.huntercoles.splatman

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device smoke test tools/e2e.sh runs: the app starts (past onboarding on a fresh device) on the library, opens
 * the bundled sample in its viewer and leaves a screenshot of it at phone and at tablet width. Screenshots go to
 * [DEVICE_DIR] (written by the shell user, so they outlive the app's uninstall after the run); the script pulls them
 * to build/e2e/.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A wiped device starts on onboarding; the first test through it lands on the library for the rest. */
    @Before
    fun passOnboarding() {
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY), TIMEOUT_MS)
    }

    @Test
    fun firstScreenShowsTheLibrary() {
        compose.onNodeWithText(LIBRARY).assertExists()
        compose.waitUntilAtLeastOneExists(hasText(SAMPLES), TIMEOUT_MS)
    }

    @Test
    fun sampleModelOpensInTheViewer() {
        compose.waitUntilAtLeastOneExists(hasText(SAMPLE_MODEL), TIMEOUT_MS)
        compose.onNodeWithText(SAMPLE_MODEL).performClick()
        waitForLoadedScene()
        screenshot("library-viewer-phone.png")

        try {
            shell("wm size $TABLET_SIZE")
            shell("wm density $TABLET_DENSITY")
            // The activity is recreated at the new size; the loaded scene lives in the ViewModel and survives it.
            waitForLoadedScene()
            screenshot("library-viewer-tablet.png")
        } finally {
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    @Test
    fun statsToggleShowsTheStatsLine() {
        compose.waitUntilAtLeastOneExists(hasText(SAMPLE_MODEL), TIMEOUT_MS)
        compose.onNodeWithText(SAMPLE_MODEL).performClick()
        waitForLoadedScene()
        check(!exists(hasText(STATS_FPS, substring = true))) { "the stats line shows before Stats is tapped" }
        // At phone width Stats sits past the right edge of the scrolling chip row: a tap there lands on nothing.
        compose.onNodeWithText(VIEWER_STATS).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(STATS_FPS, substring = true), STATS_TIMEOUT_MS)
        compose.onNode(hasText(STATS_SPLATS, substring = true)).assertExists()
    }

    @Test
    fun captureScreenOpens() {
        // The shell sees every package; the app itself would need a <queries> entry to see ARCore.
        val arCore = shell("pm path $AR_CORE").contains("package:")
        assumeTrue("SKIPPED: ARCore ($AR_CORE) is not installed on this device; capture is not tested", arCore)
        compose.onNodeWithText(SCAN).performClick()
        compose.waitForIdle()
        screenshot("capture-phone.png")
    }

    /** The viewer is up (its file chip shows) and neither loading, idle nor failed: the scene is loaded. */
    private fun waitForLoadedScene() {
        compose.waitUntil(TIMEOUT_MS) {
            exists(hasText(VIEWER_OPEN_FILE)) &&
                !exists(hasText(VIEWER_LOADING, substring = true)) &&
                !exists(hasText(VIEWER_FAILED, substring = true)) &&
                !exists(hasText(VIEWER_IDLE))
        }
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS) // the GL surface draws after Compose goes idle
        shell("mkdir -p $DEVICE_DIR")
        shell("screencap -p $DEVICE_DIR/$name")
    }

    /** Runs [command] as the shell user, waits for it to finish and returns its output. */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private companion object {
        const val ONBOARDING_START = "Skip" // the onboarding pager's Skip goes straight to the Library
        const val LIBRARY = "Library"
        const val SAMPLES = "Samples"
        const val SCAN = "Scan"
        const val SAMPLE_MODEL = "Measuring tape"
        const val VIEWER_OPEN_FILE = "Open file"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Could not open"
        const val VIEWER_STATS = "Stats"
        const val STATS_FPS = " fps "
        const val STATS_SPLATS = " splats "
        const val STATS_TIMEOUT_MS = 2_000L
        const val VIEWER_IDLE = "Pick a sample or open a .ply or .splat file."
        const val AR_CORE = "com.google.ar.core"
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TABLET_SIZE = "1600x2560"
        const val TABLET_DENSITY = 320
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_500L
    }
}
