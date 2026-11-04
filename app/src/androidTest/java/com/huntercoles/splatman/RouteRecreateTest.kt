package com.huntercoles.splatman

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huntercoles.splatman.core.navigation.ShellRoutes
import com.huntercoles.splatman.core.preferences.OnboardingPreferences
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #279: MainActivity launched with EXTRA_ROUTE and recreated before its first frame (a configuration change landing
 * just after the launch, as seen in #278) still opens the route.
 *
 * The first instance is relaunched from its post-resume callback, which runs before ActivityThread adds the window,
 * and is marked invisible there so its window is never added: it gets no traversal, no composition and no frame, so
 * the relaunch cannot lose a race with a vsync stamped earlier (#395). Its saved state must say the route was not
 * opened (the state #279 has to recover from), and the relaunched instance must still open it.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RouteRecreateTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val onboarding by lazy { OnboardingPreferences(instrumentation.targetContext) }
    private var onboardingWasDone = false
    private var registered = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private val recreator = Recreator()

    @Before
    fun libraryStart() {
        // onActivityPostResumed (the only hook between onResume and the window being added) and
        // onActivityPostSaveInstanceState exist from API 29.
        assumeTrue("needs the API 29 post-lifecycle callbacks", Build.VERSION.SDK_INT >= 29)
        onboardingWasDone = onboarding.isOnboardingDone
        onboarding.setOnboardingDone(true)
        application.registerActivityLifecycleCallbacks(recreator)
        registered = true
    }

    @After
    fun reset() {
        if (!registered) return
        application.unregisterActivityLifecycleCallbacks(recreator)
        scenario?.close()
        onboarding.setOnboardingDone(onboardingWasDone)
    }

    @Test
    fun routeOpensAfterARecreationBeforeTheFirstFrame() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, ShellRoutes.ABOUT)
        scenario = ActivityScenario.launch(intent)

        compose.waitUntil(TIMEOUT_MS) { recreator.created >= 2 }
        assertEquals("the activity was not recreated exactly once", 2, recreator.created)
        assertEquals(
            "the first instance's saved state (route opened?) before its recreation",
            false,
            recreator.firstRouteOpened,
        )
        compose.waitUntilAtLeastOneExists(hasText(ABOUT_TAGLINE), TIMEOUT_MS)
        compose.onNodeWithText(ABOUT_TAGLINE).assertIsDisplayed()
    }

    /** Recreates the first MainActivity before its window is added and notes what that instance saved. */
    private class Recreator : Application.ActivityLifecycleCallbacks {
        @Volatile var created = 0

        /** The first instance's saved `splatman.route_opened`; null if it saved no state or no such key. */
        @Volatile var firstRouteOpened: Boolean? = null
        private var first: Activity? = null
        private var recreating: Activity? = null

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            created++
            if (created == 1) first = activity
        }

        override fun onActivityPostResumed(activity: Activity) {
            if (activity !== first) return
            first = null
            recreating = activity
            // performResume has passed its "an activity without a UI must finish" check, and mVisibleFromServer is
            // still false, so this only stops handleResumeActivity from adding (and later showing) the window.
            activity.setVisible(false)
            activity.recreate()
        }

        // Not onActivitySaveInstanceState: that runs inside Activity.onSaveInstanceState, before MainActivity's
        // override has written its key.
        override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
            if (activity !== recreating) return
            firstRouteOpened = if (outState.containsKey(KEY_ROUTE_OPENED)) outState.getBoolean(KEY_ROUTE_OPENED) else null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === recreating) recreating = null
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
    }

    private companion object {
        const val ABOUT_TAGLINE = "Free, offline 3D splat scanner."
        const val KEY_ROUTE_OPENED = "splatman.route_opened"
        const val TIMEOUT_MS = 30_000L
    }
}
