package com.huntercoles.splatman

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
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
 * #113: the viewer opened in #38, end to end. Each bundled sample opens from the library with its splat count on the
 * stats overlay; the system picker (OpenDocument) is answered by a blocking [Instrumentation.ActivityMonitor] with a
 * `file://` uri in the app's cache, so no DocumentsUI is driven (the name query finds nothing, so the error title
 * reads "Could not open File"); rotation and back leave the app running.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ViewerEndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var picker: Instrumentation.ActivityMonitor? = null

    @Before
    fun openLibrary() {
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY), TIMEOUT_MS)
        compose.waitUntilAtLeastOneExists(hasText(SAMPLES), TIMEOUT_MS)
    }

    @After
    fun removePicker() {
        picker?.let(instrumentation::removeMonitor)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun a1_cactusSh1SampleShowsItsSplatCount() = sampleShowsSplatCount("Cactus sh1", "139,410")

    @Test
    fun a1_cactusSpzSampleShowsItsSplatCount() = sampleShowsSplatCount("Cactus", "139,410")

    @Test
    fun a1_measuringTapeSampleShowsItsSplatCount() = sampleShowsSplatCount("Measuring tape", "19,469")

    @Test
    fun a2_pickedValidPlyRenders() {
        openViewer("Measuring tape")
        pick(cacheFile("picked-valid.ply") { asset("samples/measuring-tape.ply") })
        waitForLoadedScene()
        showStats()
        compose.waitUntilAtLeastOneExists(hasText("19,469 splats", substring = true), STATS_TIMEOUT_MS)
    }

    @Test
    fun a2_pickedRandomBytesShowsTheErrorStateAndTheAppKeepsRunning() {
        openViewer("Measuring tape")
        pick(cacheFile("picked-random.ply") { Random(RANDOM_SEED).nextBytes(RANDOM_BYTES) })
        assertErrorStateAndAlive()
    }

    @Test
    fun a2_pickedHeaderOnlyPlyShowsTheErrorStateAndTheAppKeepsRunning() {
        openViewer("Measuring tape")
        pick(cacheFile("picked-header-only.ply") { HEADER_ONLY_PLY.toByteArray(Charsets.US_ASCII) })
        assertErrorStateAndAlive()
    }

    @Test
    fun a3_rotatingToLandscapeInTheViewerDoesNotCrash() {
        openViewer("Measuring tape")
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        waitForLoadedScene()
        assertAlive()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        waitForLoadedScene()
        assertAlive()
    }

    @Test
    fun a3_backFromTheViewerReturnsToTheLibraryWithoutACrash() {
        openViewer("Measuring tape")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText(SAMPLES), TIMEOUT_MS)
        compose.waitUntil(TIMEOUT_MS) { !exists(hasText(VIEWER_OPEN_FILE)) }
        assertAlive()
    }

    /** A strict xfail: passes while [body] fails, and fails once it passes, so [ticket] removes this wrapper. */
    private fun expectedToFail(ticket: String, body: () -> Unit) {
        val passed = runCatching(body).isSuccess
        check(!passed) { "expected to fail until $ticket, but it passes: remove expectedToFail" }
    }

    private fun sampleShowsSplatCount(sample: String, count: String) {
        openViewer(sample)
        showStats()
        compose.waitUntilAtLeastOneExists(hasText("$count splats", substring = true), STATS_TIMEOUT_MS)
        assertAlive()
    }

    private fun openViewer(sample: String) {
        compose.onNode(hasText(sample)).performScrollTo().performClick()
        waitForLoadedScene()
    }

    /** Taps Stats unless its line already shows (the ViewModel may outlive one opening). */
    private fun showStats() {
        if (exists(hasText(STATS_FPS, substring = true))) return
        // At phone width Stats sits past the right edge of the scrolling chip row.
        compose.onNodeWithText(VIEWER_STATS).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(STATS_FPS, substring = true), STATS_TIMEOUT_MS)
    }

    /** Answers the next OpenDocument with [file] and taps Open file. */
    private fun pick(file: File) {
        picker?.let(instrumentation::removeMonitor)
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) result else null
        }.also(instrumentation::addMonitor)
        compose.onNodeWithText(VIEWER_OPEN_FILE).performScrollTo().performClick()
    }

    private fun assertErrorStateAndAlive() {
        compose.waitUntilAtLeastOneExists(hasText(VIEWER_FAILED_FILE), TIMEOUT_MS)
        compose.onNodeWithText(VIEWER_RETRY).assertExists()
        assertAlive()
        // Try again reads the same broken file: the error state comes back and the app still runs.
        compose.onNodeWithText(VIEWER_RETRY).performClick()
        compose.waitUntilAtLeastOneExists(hasText(VIEWER_FAILED_FILE), TIMEOUT_MS)
        compose.onNodeWithText(VIEWER_RETRY).assertExists()
        assertAlive()
    }

    private fun assertAlive() {
        compose.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        compose.runOnUiThread { assertFalse("the activity is finishing", compose.activity.isFinishing) }
    }

    /** The viewer is up and its scene loaded; a failed load fails the test with the viewer's own error text. */
    private fun waitForLoadedScene() {
        compose.waitUntil(TIMEOUT_MS) {
            check(!exists(hasText(VIEWER_FAILED, substring = true))) { "the viewer failed to load: ${visibleText()}" }
            exists(hasText(VIEWER_OPEN_FILE)) &&
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
        const val LIBRARY = "Library"
        const val SAMPLES = "Samples"
        const val VIEWER_OPEN_FILE = "Open file"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Could not open"
        const val VIEWER_FAILED_FILE = "Could not open File"
        const val VIEWER_RETRY = "Try again"
        const val VIEWER_STATS = "Stats"
        const val STATS_FPS = " fps "
        const val VIEWER_IDLE = "Pick a sample or open a .ply or .splat file."
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
