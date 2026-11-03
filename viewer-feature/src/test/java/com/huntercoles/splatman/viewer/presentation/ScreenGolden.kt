package com.huntercoles.splatman.viewer.presentation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.huntercoles.splatman.core.design.SplatTheme
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshot goldens (#67) on the JVM: Robolectric native graphics at one phone size ([QUALIFIERS]), light or dark.
 * Each golden is `src/test/screenshots/<screen>/<screen>_phone-360x780_<light|dark>.png` in its module; a plain
 * `testDebugUnitTest` verifies it pixel for pixel, `recordRoborazziDebug` re-records it.
 *
 * Nothing in the picture comes from the machine: the text is drawn with the fonts in Robolectric's android-all jar
 * (not the host's), the Compose clock does not advance on its own and is moved a fixed [SETTLE_MS], and the JVM's
 * time zone and locale are pinned to UTC and en-US while the screen renders (put back after the test).
 *
 * Use from a test class annotated `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
 * and `@Config(sdk = [ScreenGolden.SDK], qualifiers = ScreenGolden.QUALIFIERS)`.
 *
 * The same file sits in the library, viewer and capture modules' tests (capture's tests do not share core's).
 */
class ScreenGolden : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>()

    private val restore = object : ExternalResource() {
        private var zone: TimeZone? = null
        private var locale: Locale? = null

        override fun before() {
            zone = TimeZone.getDefault()
            locale = Locale.getDefault()
        }

        override fun after() {
            zone?.let { TimeZone.setDefault(it) }
            locale?.let { Locale.setDefault(it) }
        }
    }

    private val chain: RuleChain = RuleChain.outerRule(restore).around(compose)

    override fun apply(base: Statement, description: Description): Statement = chain.apply(base, description)

    /** Renders [content] in the app theme ([dark] or light) and records, verifies or compares it as [screen]. */
    fun capture(screen: String, dark: Boolean, content: @Composable () -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
        compose.setContent { SplatTheme(darkTheme = dark) { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(path(screen, dark), OPTIONS)
    }

    companion object {
        const val SDK = 34
        const val QUALIFIERS = "en-rUS-w360dp-h780dp-port-xhdpi"
        const val ZONE = "UTC"

        /** Long enough for every enter animation and fade to finish; infinite ones stop at the same frame. */
        const val SETTLE_MS = 2_000L

        private val OPTIONS = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

        fun path(screen: String, dark: Boolean): String {
            val root = System.getProperty("roborazzi.output.dir") ?: "src/test/screenshots"
            return "$root/$screen/${screen}_phone-360x780_${if (dark) "dark" else "light"}.png"
        }
    }
}
