package com.huntercoles.splatman

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device smoke test tools/e2e.sh runs: the app starts (past onboarding on a fresh device) on the library, opens
 * the bundled sample in its viewer (through the viewer route by its id, `viewer/sample:<file>`) and leaves a screenshot of it at phone and at tablet width. Screenshots go to
 * [DEVICE_DIR] (written by the shell user, so they outlive the app's uninstall after the run); the script pulls them
 * to build/e2e/.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** A wiped device starts on onboarding; the first test through it lands on the library for the rest. */
    @Before
    fun passOnboarding() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
    }

    @After
    fun closeApp() = scenario.close()

    @Test
    fun firstScreenShowsTheLibrary() {
        check(exists(hasText(LIBRARY_TITLE))) { "the Library's title is not on screen" }
    }

    @Test
    fun sampleModelOpensInTheViewer() {
        openSample()
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
        openSample()
        check(!exists(hasText(STATS_FPS, substring = true))) { "the stats line shows before Stats is tapped" }
        // Stats is an icon button in the bottom bar, on screen at phone width (no scrolling).
        compose.onNodeWithContentDescription(VIEWER_STATS).performClick()
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

    /** Starts the app again on the bundled [SAMPLE_MODEL]'s viewer route, on top of the Library, and waits for it. */
    private fun openSample() {
        scenario.close()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, "$VIEWER_ROUTE/$SAMPLE_ID_PREFIX$SAMPLE_MODEL")
        scenario = ActivityScenario.launch(intent)
        waitForLoadedScene()
    }

    /** The viewer is up (its Share button shows) and neither loading, idle nor failed: the scene is loaded. */
    private fun waitForLoadedScene() {
        compose.waitUntil(TIMEOUT_MS) {
            exists(hasText(VIEWER_SHARE)) &&
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
        const val LIBRARY_TITLE = "Splatman" // the Library's top bar title (lib_title); the whole text
        const val SCAN = "Scan"
        const val SAMPLE_MODEL = "measuring-tape.ply"
        const val VIEWER_ROUTE = "viewer" // ShellRoutes.VIEWER
        const val SAMPLE_ID_PREFIX = "sample:" // library Sample.ID_PREFIX
        const val VIEWER_SHARE = "Share"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Can't open this splat"
        const val VIEWER_STATS = "Stats"
        const val STATS_FPS = " fps "
        const val STATS_SPLATS = " splats "
        const val STATS_TIMEOUT_MS = 2_000L
        const val VIEWER_IDLE = "Open a .ply or .splat file from the menu."
        const val AR_CORE = "com.google.ar.core"
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TABLET_SIZE = "1600x2560"
        const val TABLET_DENSITY = 320
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_500L
    }
}
