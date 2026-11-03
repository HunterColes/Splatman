package com.huntercoles.splatman

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.core.preferences.OnboardingPreferences
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
 * Onboarding at 640x360 dp (a phone held sideways) and 360x640 dp (#167): every page shows its title and first body
 * text without scrolling, and at 200 % font both bars stay on screen. Leaves a screenshot of each page in
 * [DEVICE_DIR], which tools/e2e.sh pulls to build/e2e/. On a device without ARCore the third page is the AR
 * unsupported (or couldn't check) page.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingLayoutTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val onboarding by lazy { OnboardingPreferences(instrumentation.targetContext) }
    private var onboardingWasDone = false

    /**
     * Onboarding is opened as the start destination (first run), not by EXTRA_ROUTE (#278): a global configuration
     * change can still land just after the launch and relaunch the activity, and a relaunched MainActivity has a saved
     * state, so it drops EXTRA_ROUTE and shows the Library. The start destination survives the relaunch.
     */
    @Before
    fun firstRun() {
        onboardingWasDone = onboarding.isOnboardingDone
        onboarding.setOnboardingDone(false)
    }

    @After
    fun reset() {
        scenario?.close()
        onboarding.setOnboardingDone(onboardingWasDone)
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
        awaitConfiguration(density = physicalDensity(), orientation = null, fontScale = 1f)
    }

    @Test
    fun landscapeShowsEveryTitleAndBody() = walkPages(LANDSCAPE_SIZE, "landscape")

    @Test
    fun portraitShowsEveryTitleAndBody() = walkPages(PORTRAIT_SIZE, "portrait")

    @Test
    fun landscapeAtDoubleFontKeepsTheBars() {
        shell("settings put system font_scale 2.0")
        openOnboarding(LANDSCAPE_SIZE, fontScale = 2f)
        compose.onNodeWithText(SKIP).assertIsDisplayed()
        compose.onNodeWithText(NEXT).assertIsDisplayed()
        screenshot("onboarding-landscape-font200.png")
    }

    /**
     * #276 (#167's focus Acceptance): at 640x360 dp, Tab walks Skip, then the bar's text button, then its filled
     * button (one row, text button first); the page's title and body sit between Skip and the bar.
     */
    @Test
    fun landscapeFocusOrderIsSkipPageSecondaryPrimary() = walkFocus(LANDSCAPE_SIZE, landscape = true)

    /**
     * #276: at 360x640 dp the bar is a column with the filled button over the text button, so Tab walks Skip, the
     * filled button, then the text button; the title and body sit between Skip and the bar.
     */
    @Test
    fun portraitFocusOrderIsSkipPagePrimarySecondary() = walkFocus(PORTRAIT_SIZE, landscape = false)

    /** Checks the focus order on the welcome page, then on the camera (AR) page in whatever AR state the device has. */
    private fun walkFocus(size: String, landscape: Boolean) {
        openOnboarding(size)
        assertFocusOrder(WELCOME_TITLE, WELCOME_BODY, BarButtons(NEXT, null), landscape, "welcome")

        compose.onNodeWithText(NEXT).performClick()
        compose.waitUntilAtLeastOneExists(hasText(TIPS_TITLE), TIMEOUT_MS)
        compose.onNodeWithText(NEXT).performClick()
        compose.waitUntil(TIMEOUT_MS) { !exists(hasText(NEXT)) && CAMERA_PAGES.keys.any { exists(hasText(it)) } }
        compose.waitForIdle()
        val title = CAMERA_PAGES.keys.first { exists(hasText(it)) }
        val (body, bar) = checkNotNull(CAMERA_PAGES[title])
        assertFocusOrder(title, body, bar, landscape, "camera ($title)")
    }

    private fun assertFocusOrder(title: String, body: String, bar: BarButtons, landscape: Boolean, page: String) {
        val buttons = when {
            bar.secondary == null -> listOf(bar.primary)
            landscape -> listOf(bar.secondary, bar.primary)
            else -> listOf(bar.primary, bar.secondary)
        }
        assertEquals("Tab order on the $page page", listOf(SKIP) + buttons, tabOrder())

        // The page content is read between Skip and the bar: below Skip, above every bar button.
        val skip = boundsOf(SKIP)
        val content = listOf(boundsOf(title), boundsOf(body, substring = true))
        val barTop = buttons.minOf { boundsOf(it).top }
        content.forEach {
            assertTrue("$page: content $it should be under Skip $skip", it.top >= skip.bottom)
            assertTrue("$page: content $it should be over the bar (top $barTop)", it.bottom <= barTop)
        }
    }

    /**
     * Presses Tab as a hardware keyboard would (through the window, so the app leaves touch mode) until focus comes
     * back to a stop it has seen, and returns the cycle of focused labels starting at Skip. Tab wraps, and focus may
     * already sit anywhere (a relaunch, an earlier page), so the cycle, not the first stop, is the order.
     */
    private fun tabOrder(): List<String> {
        val order = mutableListOf<String>()
        repeat(MAX_TAB_STOPS) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            val label = focusedLabel()
            if (label in order) {
                val cycle = order.drop(order.indexOf(label))
                val start = cycle.indexOf(SKIP).coerceAtLeast(0)
                return cycle.drop(start) + cycle.take(start)
            }
            order += label
        }
        return order
    }

    private fun focusedLabel(): String {
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        if (focused.isEmpty()) return "<nothing>"
        val config = focused.single().config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return text ?: description ?: "<unlabelled ${focused.single().id}>"
    }

    private fun boundsOf(text: String, substring: Boolean = false): Rect =
        compose.onNodeWithText(text, substring = substring).fetchSemanticsNode().boundsInRoot

    private fun walkPages(size: String, name: String) {
        openOnboarding(size)
        compose.onNodeWithText(WELCOME_TITLE).assertIsDisplayed()
        compose.onNodeWithText(WELCOME_BODY, substring = true).assertIsDisplayed()
        screenshot("onboarding-$name-1-welcome.png")

        compose.onNodeWithText(NEXT).performClick()
        compose.waitUntilAtLeastOneExists(hasText(TIPS_TITLE), TIMEOUT_MS)
        compose.onNodeWithText(TIPS_TITLE).assertIsDisplayed()
        compose.onNodeWithText(TIP_SLOW).assertIsDisplayed()
        screenshot("onboarding-$name-2-tips.png")

        compose.onNodeWithText(NEXT).performClick()
        // The camera page has no Next; wait for it to settle on one of its AR states.
        compose.waitUntil(TIMEOUT_MS) { !exists(hasText(NEXT)) && CAMERA_TITLES.any { exists(hasText(it)) } }
        val title = CAMERA_TITLES.first { exists(hasText(it)) }
        compose.onNodeWithText(title).assertIsDisplayed()
        screenshot("onboarding-$name-3-camera.png")
    }

    /**
     * Sets the display to [size] px at [DENSITY] (so 1280x720 is 640x360 dp) and opens the app on onboarding (see
     * [firstRun]). `wm` returns before the app process has the new configuration, so the launch waits for it.
     */
    private fun openOnboarding(size: String, fontScale: Float = 1f) {
        shell("wm size $size")
        shell("wm density $DENSITY")
        val (width, height) = size.split("x").map(String::toInt)
        val orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        awaitConfiguration(DENSITY, orientation, fontScale)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntilAtLeastOneExists(hasText(WELCOME_TITLE), TIMEOUT_MS)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("onboarding did not open:\n${whatIsShown()}", e)
        }
        compose.waitForIdle()
    }

    /** What is up when onboarding does not open: the flag, the focused window, wakefulness, the Compose trees. */
    private fun whatIsShown(): String {
        screenshot("onboarding-not-opened.png")
        val trees = runCatching {
            val roots = compose.onAllNodes(isRoot())
            roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
        }.getOrElse { "no compose tree: $it" }
        val window = shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "mFocusedApp" in it }
        val power = shell("dumpsys power").lines().filter { "mWakefulness=" in it }
        return "onboardingDone=${onboarding.isOnboardingDone} config=${appConfig()}\n" +
            (window + power).joinToString("\n") { it.trim() } + "\n" + trees
    }

    private fun appConfig(): Configuration = instrumentation.targetContext.applicationContext.resources.configuration

    /** Waits until the app process's configuration has [density], [orientation] (null: any) and [fontScale]. */
    private fun awaitConfiguration(density: Int, orientation: Int?, fontScale: Float) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            val config = appConfig()
            val settled = config.densityDpi == density &&
                (orientation == null || config.orientation == orientation) &&
                config.fontScale == fontScale
            if (settled) break
            check(System.currentTimeMillis() < end) {
                "configuration stayed at $config, wanted density $density, orientation $orientation, font $fontScale"
            }
            Thread.sleep(POLL_MS)
        }
        instrumentation.waitForIdleSync()
    }

    /** The display's own density, from `wm density` ("Physical density: 420"). */
    private fun physicalDensity(): Int =
        checkNotNull(PHYSICAL_DENSITY.find(shell("wm density"))) { "no physical density from wm" }
            .groupValues[1].toInt()

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("mkdir -p $DEVICE_DIR")
        shell("screencap -p $DEVICE_DIR/$name")
    }

    /** Runs [command] as the shell user, waits for it to finish and returns its output. */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    /** A page's bottom bar: the filled button and the text button (null: none). */
    private data class BarButtons(val primary: String, val secondary: String?)

    private companion object {
        const val LANDSCAPE_SIZE = "1280x720"
        const val PORTRAIT_SIZE = "720x1280"
        const val DENSITY = 320
        const val SKIP = "Skip"
        const val NEXT = "Next"
        const val WELCOME_TITLE = "Scan anything in 3D"
        const val WELCOME_BODY = "Walk around something with your phone."
        const val TIPS_TITLE = "For a good scan"
        const val TIP_SLOW = "Move slowly, like you are carrying a full cup"
        val CAMERA_TITLES = listOf(
            "Splatman needs your camera",
            "This phone can't track motion",
            "ARCore is not installed (needs Google Play)",
            "Couldn't check ARCore",
        )
        /** Camera page title to its body text and bar, per AR state (OnboardingScreen's BottomBar). */
        val CAMERA_PAGES = mapOf(
            "Splatman needs your camera" to ("The camera sees the object" to BarButtons("Allow camera", null)),
            "This phone can't track motion" to
                ("Scanning needs ARCore, which" to BarButtons("Open a file", "Continue")),
            "ARCore is not installed (needs Google Play)" to
                ("Scanning needs Google Play Services for AR." to BarButtons("Install", "Later")),
            "Couldn't check ARCore" to ("The phone didn't answer" to BarButtons("Try again", "Continue")),
        )
        const val MAX_TAB_STOPS = 12
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_000L
        const val POLL_MS = 50L
        val PHYSICAL_DENSITY = Regex("Physical density: ([0-9]+)")
    }
}
