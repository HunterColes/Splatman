package com.huntercoles.splatman

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.splat.io.bundle.CaptureBundleReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #41's Record / Stop / Cancel end to end, as #16's Start capture / Finish / Close and Discard on a device without ARCore: [FakeCaptureTrackerModule] swaps the ARCore
 * tracker for [FakeCaptureTracker], which walks a circle. Each test starts on the Capture screen with the tracker
 * running and compares `files/scans/` with what was there before it (earlier tests may have left scans).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordStopCancelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val filesDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val scansDir get() = File(filesDir, SCANS)
    private val recordingDir get() = File(filesDir, RECORDING)
    private lateinit var scansBefore: Set<String>

    @Before
    fun openCapture() {
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
        scansBefore = scanFolders()
        // The label sits inside the button: the FAB's Scan, or the empty Library's Start a scan (it has no FAB).
        compose.onAllNodes(hasText(SCAN) or hasText(START_SCAN), useUnmergedTree = true).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(START_ENABLED, TIMEOUT_MS)
    }

    @Test
    fun a1_recordThenFinishSavesALoadableBundleWithALibraryCard() {
        compose.onNode(START_ENABLED).performClick()
        val first = waitForProgress { it.keyframes > 0 && it.coveragePercent > 0 }
        val later = waitForProgress { it.keyframes > first.keyframes && it.coveragePercent > first.coveragePercent }

        // Finish stays locked until the coverage threshold: the fake rises after its first turn.
        compose.onNode(FINISH).assertIsNotEnabled()
        compose.waitUntilAtLeastOneExists(FINISH and isEnabled(), FINISH_TIMEOUT_MS)
        compose.onNode(FINISH).performClick()
        val id = waitForNewScans(1).single()

        val folder = File(scansDir, id)
        assertTrue("$folder has no transforms.json", File(folder, TRANSFORMS).isFile)
        val bundle = CaptureBundleReader.read(folder)
        assertTrue(
            "bundle holds ${bundle.frames.size} frames, the screen showed ${later.keyframes}",
            bundle.frames.size >= later.keyframes,
        )
        assertTrue("recording/ still holds ${stagingEntries()}", stagingEntries().isEmpty())

        // Finish opens Processing (in place of Capture); Back from it lands on the library, which lists the scan by
        // its folder name.
        compose.waitUntil(TIMEOUT_MS) { !exists(FINISH) && !exists(hasContentDescription(START)) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
        compose.waitUntilAtLeastOneExists(hasText(id), TIMEOUT_MS)
    }

    @Test
    fun a2_recordThenCloseAndDiscardLeavesNoFolderUnderScansOrRecording() {
        compose.onNode(START_ENABLED).performClick()
        waitForProgress { it.keyframes > 0 }

        compose.onNode(hasContentDescription(CLOSE)).performClick()
        compose.waitUntilAtLeastOneExists(hasText(DISCARD) and hasClickAction(), TIMEOUT_MS)
        compose.onNode(hasText(DISCARD) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
        Thread.sleep(SETTLE_MS) // a save that wrongly ran would have finished by now

        assertEquals(scansBefore, scanFolders())
        assertTrue("recording/ still holds ${stagingEntries()}", stagingEntries().isEmpty())
    }

    @Test
    fun a2_doubleTapFinishWritesOneBundle() {
        compose.onNode(START_ENABLED).performClick()
        compose.waitUntilAtLeastOneExists(FINISH and isEnabled(), FINISH_TIMEOUT_MS)

        // Both taps land in the same frame, before the screen can swap Finish for the saving state.
        val onClick = compose.onNode(FINISH).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        compose.runOnUiThread {
            onClick?.invoke()
            onClick?.invoke()
        }

        val id = waitForNewScans(1).single()
        Thread.sleep(SETTLE_MS) // time for a second save to land, if one was started
        assertEquals(scansBefore + id, scanFolders())
        CaptureBundleReader.read(File(scansDir, id))
        assertTrue("recording/ still holds ${stagingEntries()}", stagingEntries().isEmpty())
    }

    private data class Progress(val keyframes: Int, val coveragePercent: Int)

    /** Waits until the caption ("m:ss · n frames") and the ring ("p% covered") show values matching [ok]. */
    private fun waitForProgress(ok: (Progress) -> Boolean): Progress {
        var seen: Progress? = null
        compose.waitUntil(TIMEOUT_MS) {
            seen = progress()
            seen?.let(ok) == true
        }
        return checkNotNull(seen)
    }

    private fun progress(): Progress? {
        val frames = texts(FRAMES_MARK).firstNotNullOfOrNull { FRAMES.find(it) } ?: return null
        val covered = texts(COVERED_MARK).firstNotNullOfOrNull { COVERED.find(it) } ?: return null
        return Progress(frames.groupValues[1].toInt(), covered.groupValues[1].toInt())
    }

    private fun texts(mark: String): List<String> =
        compose.onAllNodes(hasText(mark, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }

    /** Waits until [count] scan folders not there before the test exist, each with its transforms.json. */
    private fun waitForNewScans(count: Int): Set<String> {
        var fresh = emptySet<String>()
        compose.waitUntil(TIMEOUT_MS) {
            fresh = scanFolders() - scansBefore
            fresh.size >= count && fresh.all { File(File(scansDir, it), TRANSFORMS).isFile }
        }
        return fresh
    }

    private fun scanFolders(): Set<String> =
        scansDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

    private fun stagingEntries(): List<String> = recordingDir.list()?.toList().orEmpty()

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val ONBOARDING_START = "Skip"
        const val LIBRARY_TITLE = "Splatman" // the Library's top bar title (lib_title); the whole text
        const val SCAN = "Scan"
        const val START_SCAN = "Start a scan"
        const val START = "Start capture"
        const val CLOSE = "Close"
        const val DISCARD = "Discard"
        val START_ENABLED = hasContentDescription(START) and isEnabled()
        val FINISH = hasText("Finish") and hasClickAction()
        const val FRAMES_MARK = " frames"
        val FRAMES = Regex("""\d+:\d\d · (\d+) frames""")
        const val COVERED_MARK = "% covered"
        val COVERED = Regex("""(\d+)% covered""")
        const val FINISH_TIMEOUT_MS = 60_000L
        const val SCANS = "scans"
        const val RECORDING = "recording"
        const val TRANSFORMS = "transforms.json"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 3_000L
    }
}
