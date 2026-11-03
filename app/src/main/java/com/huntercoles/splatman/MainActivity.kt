package com.huntercoles.splatman

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.ShellRoutes
import com.huntercoles.splatman.core.preferences.OnboardingPreferences
import com.huntercoles.splatman.core.preferences.ThemePreferences
import com.huntercoles.splatman.library.importing.ImportRequests
import com.huntercoles.splatman.shell.SplatmanShell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single activity: edge-to-edge, hosting the app shell. It starts at Onboarding on first run, else the Library.
 * An intent may carry [EXTRA_ROUTE] (a route path such as `processing/{scanId}`) to open on top of the start. It
 * stays pending across a recreation until the shell has opened it (#279): a configuration change that relaunches the
 * activity before its first frame keeps a saved state without the route, so the saved state alone cannot tell.
 * An `ACTION_VIEW` intent ("Open with" on a `.ply` or `.splat`) hands its file to the Library's import flow.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var onboardingPreferences: OnboardingPreferences

    @Inject
    lateinit var themePreferences: ThemePreferences

    @Inject
    lateinit var importRequests: ImportRequests

    /** Whether the shell has navigated to this intent's [EXTRA_ROUTE]; saved across recreations. */
    private var routeOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = ShellRoutes.startDestination(onboardingPreferences.isOnboardingDone)
        routeOpened = savedInstanceState?.getBoolean(KEY_ROUTE_OPENED) ?: false
        val requested = pendingRoute(intent?.getStringExtra(EXTRA_ROUTE), routeOpened)
        if (savedInstanceState == null) offerImport(intent)
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle()
            SplatmanShell(
                startDestination = start,
                themeMode = themeMode,
                requestedRoute = requested,
                onRequestedRouteOpened = { routeOpened = true },
                onOnboardingDone = { onboardingPreferences.setOnboardingDone(true) },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ROUTE_OPENED, routeOpened)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerImport(intent)
    }

    private fun offerImport(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let { importRequests.offer(it.toString()) }
    }

    companion object {
        const val EXTRA_ROUTE = ShellRoutes.EXTRA_ROUTE
        private const val KEY_ROUTE_OPENED = "splatman.route_opened"

        /** The route still to open: the intent's [extra] path, unless the shell already [opened] it. */
        internal fun pendingRoute(extra: String?, opened: Boolean): NavigationDestination? =
            if (opened) null else extra?.let(ShellRoutes::parse)
    }
}
