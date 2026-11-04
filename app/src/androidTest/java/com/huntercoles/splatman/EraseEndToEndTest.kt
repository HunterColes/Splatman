package com.huntercoles.splatman

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

/**
 * #332: the Viewer's erase mode (docs/design/screens/erase.md) end to end on the bundled cactus sample, at phone and
 * at tablet width. Erase opens from the bar, Apply stays off until a one-finger swipe across the render marks splats
 * (the count pill shows them), Apply cuts exactly that many (the render's description, the "Removed X splats. N left"
 * snackbar and the details sheet all show N = 139,410 - X), and More options > Revert edits > Revert brings back
 * 139,410. A screenshot of erase mode with marks lands in build/e2e (erase-phone.png, erase-tablet.png). Leftover edits
 * of an earlier run are reverted first, and every pass reverts in a finally.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EraseEndToEndTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
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
        scenario.close()
    }

    @Test
    fun eraseApplyRevertOnCactusAtPhoneAndTabletWidth() {
        openCactus()
        if (renderCount() != CACTUS_COUNT) revert()
        eraseApplyRevert("erase-phone.png")

        try {
            shell("wm size $TABLET_SIZE")
            shell("wm density $TABLET_DENSITY")
            // The activity is recreated at the new size; the loaded scene lives in the ViewModel and survives it.
            waitForLoadedScene()
            eraseApplyRevert("erase-tablet.png")
        } finally {
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    private fun eraseApplyRevert(shot: String) {
        waitForRenderCount { it == CACTUS_COUNT }
        var applied = false
        try {
            compose.onNodeWithContentDescription(ERASE).performClick()
            compose.waitUntilAtLeastOneExists(hasText(ERASE_HINT), TIMEOUT_MS)
            // Nothing marked: Apply is off and no count shows.
            compose.onNode(hasText(ERASE_APPLY) and isEnabled().not()).assertExists()
            assertTrue(!exists(hasText(MARKED, substring = true)))

            // One finger across the middle of the render paints a stroke through the cactus.
            compose.onNode(hasContentDescription(RENDER_DESC, substring = true)).performTouchInput {
                swipe(Offset(width * 0.3f, height * 0.45f), Offset(width * 0.7f, height * 0.5f), SWIPE_MS)
            }
            compose.waitUntil(TIMEOUT_MS) { exists(hasText(ERASE_APPLY) and isEnabled()) }
            val marked = markedCount()
            assertTrue("the stroke marked $marked", marked in 1 until CACTUS_COUNT)
            screenshot(shot)

            compose.onNodeWithText(ERASE_APPLY).performClick()
            applied = true
            val count = waitForRenderCount { it < CACTUS_COUNT }
            assertEquals("splats left after erasing $marked", CACTUS_COUNT - marked, count)
            compose.waitUntilAtLeastOneExists(
                hasText("Removed ${format(marked)} splats. ${format(count)} left"),
                TIMEOUT_MS,
            )

            // The details sheet shows the new count.
            compose.onNodeWithContentDescription(VIEWER_DETAILS).performClick()
            compose.waitUntilAtLeastOneExists(hasText("${format(count)} splats"), TIMEOUT_MS)
            shell("input keyevent KEYCODE_BACK")
            compose.waitUntil(TIMEOUT_MS) { !exists(hasText("${format(count)} splats")) }
        } finally {
            if (applied) {
                revert()
                waitForRenderCount { it == CACTUS_COUNT }
            }
        }
    }

    private fun markedCount(): Int {
        val text = compose.onAllNodes(hasText(MARKED, substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }
            .firstOrNull() ?: throw AssertionError("no count pill")
        return MARKED_COUNT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toInt()
            ?: throw AssertionError("count pill reads $text")
    }

    private fun revert() {
        compose.onNodeWithContentDescription(VIEWER_MORE).performClick()
        compose.waitUntilAtLeastOneExists(hasText(REVERT), TIMEOUT_MS)
        compose.onNodeWithText(REVERT).performClick()
        compose.waitUntilAtLeastOneExists(hasText(REVERT_CONFIRM), TIMEOUT_MS)
        compose.onNodeWithText(REVERT_CONFIRM).performClick()
        waitForLoadedScene()
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
            !exists(hasText(VIEWER_LOADING, substring = true)) && exists(hasContentDescription(ERASE) and isEnabled())
        }
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun format(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS) // the GL surface draws the marked look after Compose goes idle
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
        const val RENDER_DESC = "3D view of " // view_a11y_render, erase_render_desc
        val RENDER_COUNT = Regex(", ([0-9,]+) splats$")
        const val VIEWER_LOADING = "Opening "
        const val VIEWER_FAILED = "Can't open this splat"
        const val VIEWER_MORE = "More options"
        const val VIEWER_DETAILS = "Details" // view_info
        const val ERASE = "Erase" // erase_action, the bottom bar's icon
        const val ERASE_HINT = "Paint over what to remove. Two fingers to turn."
        const val ERASE_APPLY = "Apply"
        const val MARKED = "marked" // erase_count
        val MARKED_COUNT = Regex("^([0-9,]+) splats? marked$")
        const val REVERT = "Revert edits" // crop_revert
        const val REVERT_CONFIRM = "Revert"
        const val SWIPE_MS = 600L
        const val TABLET_SIZE = "1600x2560"
        const val TABLET_DENSITY = "320"
        const val SETTLE_MS = 1_500L
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TIMEOUT_MS = 60_000L
    }
}
