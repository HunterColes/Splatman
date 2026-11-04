package com.huntercoles.splatman

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #249: the Viewer's box crop (#88) end to end on the bundled cactus sample, at phone and at tablet width. Crop mode
 * opens, the Bottom side moves in with In, Apply cuts the scene (the render's description and the "Cropped. N splats
 * left" snackbar both show N < 139,410), Save to device writes a PLY whose `element vertex` is N (CreateDocument is
 * answered by an [Instrumentation.ActivityMonitor] with a `file://` uri in the app's cache), and More options >
 * Revert edits > Revert brings back 139,410. A screenshot of crop mode lands in build/e2e (crop-phone.png,
 * crop-tablet.png). A crop left behind by an earlier run is reverted first, and every pass reverts in a finally.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CropEndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var saver: Instrumentation.ActivityMonitor? = null
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openLibrary() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        if (exists(hasText(ONBOARDING_START))) compose.onNodeWithText(ONBOARDING_START).performClick()
        compose.waitUntilAtLeastOneExists(hasText(LIBRARY_TITLE), TIMEOUT_MS)
    }

    @After
    fun closeApp() {
        saver?.let(instrumentation::removeMonitor)
        scenario.close()
    }

    @Test
    fun a1_a2_a3_a4_cropApplyExportRevertOnCactusAtPhoneAndTabletWidth() {
        openCactus()
        revertLeftoverCrop()
        cropApplyExportRevert("crop-phone.png")

        try {
            shell("wm size $TABLET_SIZE")
            shell("wm density $TABLET_DENSITY")
            // The activity is recreated at the new size; the loaded scene lives in the ViewModel and survives it.
            waitForLoadedScene()
            cropApplyExportRevert("crop-tablet.png")
        } finally {
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    /** One pass of A1-A3 on the loaded cactus, leaving [shot] of crop mode; the scene is reverted however it ends. */
    private fun cropApplyExportRevert(shot: String) {
        waitForRenderCount { it == CACTUS_COUNT }
        var cropped = false
        try {
            // A1: Crop, select the Bottom side, nudge it in, Apply.
            // Crop mode redraws its handles every frame (CropOverlay's withFrameNanos loop), so Compose never goes
            // idle there: the test clock stops advancing on its own and frames are stepped by hand until Apply.
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithContentDescription(CROP).performClick()
                stepUntil { exists(hasText(CROP_APPLY)) }
                compose.onNodeWithText(CROP_BOTTOM).performClick()
                repeat(NUDGES) {
                    stepUntil { exists(hasContentDescription(CROP_IN_BOTTOM) and isEnabled()) }
                    compose.onNodeWithContentDescription(CROP_IN_BOTTOM).performClick()
                    compose.mainClock.advanceTimeByFrame()
                }
                stepFor(SETTLE_MS)
                screenshot(shot)
                compose.onNodeWithText(CROP_APPLY).performClick()
                cropped = true
                stepUntil { !exists(hasText(CROP_APPLY)) }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            val count = waitForRenderCount { it < CACTUS_COUNT }
            assertTrue("the crop cut nothing: $count", count in 1 until CACTUS_COUNT)
            compose.waitUntilAtLeastOneExists(hasText("Cropped. ${format(count)} splats left"), TIMEOUT_MS)

            // A3: Share > PLY > Save to device; the written header names the cropped count.
            val file = saveCroppedPly()
            assertEquals("element vertex in ${file.name}", count, elementVertex(file))
        } finally {
            if (cropped) {
                // A2: More options > Revert edits > Revert brings the original back.
                revert()
                waitForRenderCount { it == CACTUS_COUNT }
            }
        }
    }

    private fun saveCroppedPly(): File {
        val file = File(context.cacheDir, "crop-e2e.ply").apply { delete() }
        saver?.let(instrumentation::removeMonitor)
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        saver = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) result else null
        }.also(instrumentation::addMonitor)
        compose.onNodeWithText(VIEWER_SHARE).performClick()
        compose.waitUntilAtLeastOneExists(hasText(EXPORT_SAVE), TIMEOUT_MS)
        compose.onNodeWithText(EXPORT_PLY).performClick()
        compose.onNodeWithText(EXPORT_SAVE).performClick()
        compose.waitUntilAtLeastOneExists(hasText(EXPORT_SAVED, substring = true), TIMEOUT_MS)
        check(file.length() > 0) { "nothing was written to ${file.path}" }
        return file
    }

    private fun revert() {
        compose.onNodeWithContentDescription(VIEWER_MORE).performClick()
        compose.waitUntilAtLeastOneExists(hasText(CROP_REVERT), TIMEOUT_MS)
        compose.onNodeWithText(CROP_REVERT).performClick()
        compose.waitUntilAtLeastOneExists(hasText(CROP_REVERT_CONFIRM), TIMEOUT_MS)
        compose.onNodeWithText(CROP_REVERT_CONFIRM).performClick()
        waitForLoadedScene()
    }

    /** A crop an earlier, broken run left on the sample: revert it so the pass starts at 139,410. */
    private fun revertLeftoverCrop() {
        if (renderCount() == CACTUS_COUNT) return
        revert()
    }

    /** Reads `element vertex N` from the PLY header in [file]. */
    private fun elementVertex(file: File): Int {
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(HEADER_MAX)
            val n = input.read(bytes)
            String(bytes, 0, maxOf(n, 0), Charsets.US_ASCII).substringBefore("end_header")
        }
        val line = header.lineSequence().firstOrNull { it.startsWith("element vertex ") }
            ?: throw AssertionError("no element vertex line in ${file.name}: $header")
        return line.removePrefix("element vertex ").trim().toInt()
    }

    private fun openCactus() {
        scenario.close()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, "$VIEWER_ROUTE/$SAMPLE_ID_PREFIX$CACTUS")
        scenario = ActivityScenario.launch(intent)
        waitForLoadedScene()
    }

    /** Waits until the render's description ("3D view of <title>, N splats") holds a count that passes [ok]. */
    private fun waitForRenderCount(ok: (Int) -> Boolean): Int {
        var last: Int? = null
        runCatching {
            compose.waitUntil(TIMEOUT_MS) {
                last = renderCount()
                last?.let(ok) == true
            }
        }.onFailure { throw AssertionError("render description count stayed at $last", it) }
        return checkNotNull(last)
    }

    private fun renderCount(): Int? = compose.onAllNodes(hasContentDescription(RENDER_DESC, substring = true))
        .fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
        .firstNotNullOfOrNull { RENDER_COUNT.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }

    private fun waitForLoadedScene() {
        compose.waitUntil(TIMEOUT_MS) {
            check(!exists(hasText(VIEWER_FAILED))) { "the viewer failed to load" }
            exists(hasContentDescription(VIEWER_STATS)) &&
                !exists(hasText(VIEWER_LOADING, substring = true)) &&
                exists(hasContentDescription(CROP) and isEnabled())
        }
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun format(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

    /** With the test clock held: steps frames (and real time, for work off the main thread) until [done]. */
    private fun stepUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out stepping the clock in crop mode" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(STEP_MS)
        }
    }

    private fun stepFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(STEP_MS)
        }
    }

    /** Crop mode never idles (see [cropApplyExportRevert]), so this waits a fixed time for the GL surface. */
    private fun screenshot(name: String) {
        Thread.sleep(SETTLE_MS) // the GL surface draws after Compose goes idle
        shell("mkdir -p $DEVICE_DIR")
        shell("screencap -p $DEVICE_DIR/$name")
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private companion object {
        const val ONBOARDING_START = "Skip"
        const val LIBRARY_TITLE = "Splatman"
        const val VIEWER_ROUTE = "viewer" // ShellRoutes.VIEWER
        const val SAMPLE_ID_PREFIX = "sample:" // library Sample.ID_PREFIX
        const val CACTUS = "cactus-sh1.ply"
        const val CACTUS_COUNT = 139_410
        const val RENDER_DESC = "3D view of " // view_a11y_render
        val RENDER_COUNT = Regex(", ([0-9,]+) splats$")
        const val VIEWER_STATS = "Stats"
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Can't open this splat"
        const val VIEWER_MORE = "More options"
        const val VIEWER_SHARE = "Share"
        const val CROP = "Crop" // crop_action, the bottom bar's icon
        const val CROP_BOTTOM = "Bottom" // crop_face_bottom, the face chip
        const val CROP_IN_BOTTOM = "Move Bottom face in 1 cm" // crop_in_desc
        const val CROP_APPLY = "Apply"
        const val CROP_REVERT = "Revert edits" // crop_revert
        const val CROP_REVERT_CONFIRM = "Revert"
        const val EXPORT_PLY = "PLY (standard 3DGS)"
        const val EXPORT_SAVE = "Save to device"
        const val EXPORT_SAVED = "Saved "
        const val NUDGES = 3
        const val STEP_MS = 16L
        const val HEADER_MAX = 4096
        const val TABLET_SIZE = "1600x2560"
        const val TABLET_DENSITY = "320"
        const val SETTLE_MS = 1_500L
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TIMEOUT_MS = 60_000L
    }
}
