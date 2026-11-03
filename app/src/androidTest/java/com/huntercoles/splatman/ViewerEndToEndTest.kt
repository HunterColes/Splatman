package com.huntercoles.splatman

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * #113: the viewer opened in #38, end to end. Each bundled sample opens through the viewer route by its id
 * (`viewer/sample:<file>`, the activity's route extra; the library no longer lists samples by name) with its splat
 * count on the stats overlay; the system picker (OpenDocument) is answered by a blocking [Instrumentation.ActivityMonitor] with a
 * `file://` uri in the app's cache, so no DocumentsUI is driven; a broken file shows the error state with Try again;
 * rotation and back leave the app running. Open file sits in the top bar's overflow menu (#162), so a loaded scene is
 * told by the bottom bar's Stats toggle, which the viewer shows only over a loaded scene.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ViewerEndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var picker: Instrumentation.ActivityMonitor? = null
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** Passes onboarding on a fresh device, so the viewer opened next sits on top of the Library (home). */
    @Before
    fun openLibrary() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
    }

    @After
    fun closeApp() {
        picker?.let(instrumentation::removeMonitor)
        scenario.close()
    }

    @Test
    fun a1_cactusSh1SampleShowsItsSplatCount() = sampleShowsSplatCount("cactus-sh1.ply", "139,410")

    @Test
    fun a1_cactusSpzSampleShowsItsSplatCount() = sampleShowsSplatCount("cactus.spz", "452,451")

    @Test
    fun a1_measuringTapeSampleShowsItsSplatCount() = sampleShowsSplatCount("measuring-tape.ply", "19,469")

    @Test
    fun a2_pickedValidPlyRenders() {
        openViewer(MEASURING_TAPE)
        pick(cacheFile("picked-valid.ply") { asset("samples/measuring-tape.ply") })
        waitForLoadedScene()
        showStats()
        compose.waitUntilAtLeastOneExists(hasText("19,469 splats", substring = true), STATS_TIMEOUT_MS)
    }

    @Test
    fun a2_pickedRandomBytesShowsTheErrorStateAndTheAppKeepsRunning() {
        openViewer(MEASURING_TAPE)
        pick(cacheFile("picked-random.ply") { Random(RANDOM_SEED).nextBytes(RANDOM_BYTES) })
        assertErrorStateAndAlive()
    }

    @Test
    fun a2_pickedHeaderOnlyPlyShowsTheErrorStateAndTheAppKeepsRunning() {
        openViewer(MEASURING_TAPE)
        pick(cacheFile("picked-header-only.ply") { HEADER_ONLY_PLY.toByteArray(Charsets.US_ASCII) })
        assertErrorStateAndAlive()
    }

    @Test
    fun a3_rotatingToLandscapeInTheViewerDoesNotCrash() {
        openViewer(MEASURING_TAPE)
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        waitForLoadedScene()
        assertAlive()
        scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        waitForLoadedScene()
        assertAlive()
    }

    @Test
    fun a3_backFromTheViewerReturnsToTheLibraryWithoutACrash() {
        openViewer(MEASURING_TAPE)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
        compose.waitUntil(TIMEOUT_MS) { !exists(hasContentDescription(VIEWER_STATS)) }
        assertAlive()
    }

    private fun sampleShowsSplatCount(sample: String, count: String) {
        openViewer(sample)
        showStats()
        runCatching {
            compose.waitUntilAtLeastOneExists(hasText("$count splats", substring = true), TIMEOUT_MS)
        }.onFailure { throw AssertionError("$sample: no '$count splats' on screen: ${visibleText()}", it) }
        assertAlive()
    }

    /**
     * Opens the bundled sample [file] through the viewer route (`viewer/sample:<file>`): the Library's app is closed and
     * started again with the route extra, which the shell opens on top of the Library.
     */
    private fun openViewer(file: String) {
        scenario.close()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, "$VIEWER_ROUTE/$SAMPLE_ID_PREFIX$file")
        scenario = ActivityScenario.launch(intent)
        waitForLoadedScene()
    }

    /** Taps Stats unless its line already shows (the ViewModel may outlive one opening). */
    private fun showStats() {
        if (exists(hasText(STATS_FPS, substring = true))) return
        compose.onNodeWithContentDescription(VIEWER_STATS).performClick()
        compose.waitUntilAtLeastOneExists(hasText(STATS_FPS, substring = true), STATS_TIMEOUT_MS)
    }

    /** Answers the next OpenDocument with [file] and taps Open file in the overflow menu. */
    private fun pick(file: File) {
        picker?.let(instrumentation::removeMonitor)
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) result else null
        }.also(instrumentation::addMonitor)
        compose.onNodeWithContentDescription(VIEWER_MORE).performClick()
        compose.waitUntilAtLeastOneExists(hasText(VIEWER_OPEN_FILE), TIMEOUT_MS)
        compose.onNodeWithText(VIEWER_OPEN_FILE).performClick()
    }

    private fun assertErrorStateAndAlive() {
        compose.waitUntilAtLeastOneExists(hasText(VIEWER_FAILED), TIMEOUT_MS)
        compose.onNodeWithText(VIEWER_RETRY).assertExists()
        assertAlive()
        // Try again reads the same broken file: the error state comes back and the app still runs.
        compose.onNodeWithText(VIEWER_RETRY).performClick()
        compose.waitUntilAtLeastOneExists(hasText(VIEWER_FAILED), TIMEOUT_MS)
        compose.onNodeWithText(VIEWER_RETRY).assertExists()
        assertAlive()
    }

    private fun assertAlive() {
        compose.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { assertFalse("the activity is finishing", it.isFinishing) }
    }

    /**
     * The viewer is up and its scene loaded (the Stats toggle shows only over a loaded scene); a failed load fails the
     * test with the viewer's own error text.
     */
    private fun waitForLoadedScene() {
        compose.waitUntil(TIMEOUT_MS) {
            check(!exists(hasText(VIEWER_FAILED))) { "the viewer failed to load: ${visibleText()}" }
            exists(hasContentDescription(VIEWER_STATS)) &&
                !exists(hasText(VIEWER_LOADING, substring = true)) &&
                !exists(hasText(VIEWER_IDLE))
        }
    }

    private fun visibleText(): String = compose.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .joinToString(" | ")

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun cacheFile(name: String, bytes: () -> ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes()) }

    private companion object {
        const val ONBOARDING_START = "Skip"
        const val LIBRARY_TITLE = "Splatman" // the Library's top bar title (lib_title); the whole text, not a substring
        const val VIEWER_ROUTE = "viewer" // ShellRoutes.VIEWER
        const val SAMPLE_ID_PREFIX = "sample:" // library Sample.ID_PREFIX
        const val MEASURING_TAPE = "measuring-tape.ply"
        const val VIEWER_OPEN_FILE = "Open file"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_MORE = "More options"
        const val VIEWER_FAILED = "Can't open this splat"
        const val VIEWER_RETRY = "Try again"
        const val VIEWER_STATS = "Stats"
        const val STATS_FPS = " fps "
        const val VIEWER_IDLE = "Open a .ply or .splat file from the menu."
        const val RANDOM_BYTES = 4000
        const val RANDOM_SEED = 113
        const val HEADER_ONLY_PLY = "ply\nformat binary_little_endian 1.0\nelement vertex 1000\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\nproperty float opacity\n" +
            "property float scale_0\nproperty float scale_1\nproperty float scale_2\n" +
            "property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\nend_header\n"
        const val TIMEOUT_MS = 60_000L
        const val STATS_TIMEOUT_MS = 5_000L
    }
}
