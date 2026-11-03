package com.huntercoles.splatman

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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

    @After
    fun reset() {
        scenario?.close()
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }

    @Test
    fun landscapeShowsEveryTitleAndBody() = walkPages(LANDSCAPE_SIZE, "landscape")

    @Test
    fun portraitShowsEveryTitleAndBody() = walkPages(PORTRAIT_SIZE, "portrait")

    @Test
    fun landscapeAtDoubleFontKeepsTheBars() {
        shell("settings put system font_scale 2.0")
        openOnboarding(LANDSCAPE_SIZE)
        compose.onNodeWithText(SKIP).assertIsDisplayed()
        compose.onNodeWithText(NEXT).assertIsDisplayed()
        screenshot("onboarding-landscape-font200.png")
    }

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

    /** Sets the display to [size] px at [DENSITY] (so 1280x720 is 640x360 dp) and opens onboarding on top. */
    private fun openOnboarding(size: String) {
        shell("wm size $size")
        shell("wm density $DENSITY")
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, ONBOARDING_ROUTE)
        scenario = ActivityScenario.launch(intent)
        compose.waitUntilAtLeastOneExists(hasText(WELCOME_TITLE), TIMEOUT_MS)
        compose.waitForIdle()
    }

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

    private companion object {
        const val ONBOARDING_ROUTE = "onboarding" // ShellRoutes.ONBOARDING
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
        const val DEVICE_DIR = "/sdcard/Download/splatman-e2e"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_000L
    }
}
