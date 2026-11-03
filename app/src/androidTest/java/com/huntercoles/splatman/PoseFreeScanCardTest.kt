package com.huntercoles.splatman

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #341: a foss pose-free scan (`capture.json` with `"poses": "none"`, `images/`, `imu.csv`, `calibration.json`, no
 * `transforms.json`) found in `files/scans/` shows in the Library as a Captured card ("waiting to build").
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PoseFreeScanCardTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val filesDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val name = "scan_e2e341_${System.currentTimeMillis()}"
    private val folder get() = File(File(filesDir, SCANS), name)
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun cleanUp() {
        scenario?.close()
        folder.deleteRecursively()
    }

    @Test
    fun captureJsonWithoutTransformsShowsACapturedCard() {
        writePoseFreeScan(folder, FRAMES)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)

        val card = hasContentDescription("$name,", substring = true) and
            hasContentDescription(CAPTURED_DESCRIPTION, substring = true)
        compose.waitUntilAtLeastOneExists(card, TIMEOUT_MS)
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The layout FossPoseFreeRecorder.stop renames into `files/scans/`, `capture.json` written last. */
    private fun writePoseFreeScan(dir: File, frames: Int) {
        File(dir, "images").mkdirs()
        repeat(frames) { File(dir, "images/frame_%05d.jpg".format(it + 1)).writeBytes(ByteArray(JPEG_BYTES)) }
        File(dir, "imu.csv").writeText("timestamp_ns,sensor,x,y,z\n")
        File(dir, "calibration.json").writeText("{}")
        val entries = (1..frames).joinToString(",") { i ->
            "{\"file_path\":\"images/frame_%05d.jpg\",\"timestamp_ns\":%d,\"w\":640,\"h\":480,\"sharpness\":40.0}"
                .format(i, i * FRAME_NS)
        }
        File(dir, "capture.json").writeText(
            "{\"splatman_capture_version\":1,\"poses\":\"none\",\"imu_file_path\":\"imu.csv\"," +
                "\"calibration_file_path\":\"calibration.json\",\"timestamp_source\":\"REALTIME\"," +
                "\"frames\":[$entries]}",
        )
    }

    private companion object {
        const val SCANS = "scans"
        const val FRAMES = 4
        const val JPEG_BYTES = 64
        const val FRAME_NS = 250_000_000L
        const val ONBOARDING_START = "Skip"
        const val LIBRARY_TITLE = "Splatman"
        const val CAPTURED_DESCRIPTION = "waiting to build"
        const val TIMEOUT_MS = 30_000L
    }
}
