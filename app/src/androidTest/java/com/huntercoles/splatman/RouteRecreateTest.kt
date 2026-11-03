package com.huntercoles.splatman

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #279: MainActivity launched with EXTRA_ROUTE and recreated before its first frame (a configuration change landing
 * just after the launch, as seen in #278) still opens the route. The first instance is relaunched from its own
 * onResume, so the relaunch runs before the window's first traversal (and so before the shell has composed).
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
    private var scenario: ActivityScenario<MainActivity>? = null
    private val recreator = Recreator()

    @Before
    fun libraryStart() {
        onboardingWasDone = onboarding.isOnboardingDone
        onboarding.setOnboardingDone(true)
        application.registerActivityLifecycleCallbacks(recreator)
    }

    @After
    fun reset() {
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
        assertFalse("the first instance drew a frame before its recreation", recreator.firstDrew)
        compose.waitUntilAtLeastOneExists(hasText(ABOUT_TAGLINE), TIMEOUT_MS)
        compose.onNodeWithText(ABOUT_TAGLINE).assertIsDisplayed()
    }

    /** Recreates the first MainActivity from its onResume and notes whether that instance ever drew. */
    private class Recreator : Application.ActivityLifecycleCallbacks {
        @Volatile var created = 0
        @Volatile var firstDrew = false
        private var first: Activity? = null

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            created++
            if (created == 1) {
                first = activity
                activity.window.decorView.viewTreeObserver.addOnDrawListener(
                    ViewTreeObserver.OnDrawListener { firstDrew = true },
                )
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity === first) {
                first = null
                activity.recreate()
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val ABOUT_TAGLINE = "Free, offline 3D splat scanner."
        const val TIMEOUT_MS = 30_000L
    }
}
