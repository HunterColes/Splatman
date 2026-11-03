package com.huntercoles.splatman

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.navigation.ShellRoutes
import com.huntercoles.splatman.core.preferences.OnboardingPreferences
import com.huntercoles.splatman.core.preferences.ThemePreferences
import com.huntercoles.splatman.library.importing.ImportRequests
import com.huntercoles.splatman.shell.SplatmanShell
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single activity: edge-to-edge, hosting the app shell. It starts at Onboarding on first run, else the Library.
 * An intent may carry [EXTRA_ROUTE] (a route path such as `processing/{scanId}`) to open on top of the start.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = ShellRoutes.startDestination(onboardingPreferences.isOnboardingDone)
        val requested = if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_ROUTE)?.let(ShellRoutes::parse)
        } else {
            null
        }
        if (savedInstanceState == null) offerImport(intent)
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle()
            SplatmanShell(
                startDestination = start,
                themeMode = themeMode,
                requestedRoute = requested,
                onOnboardingDone = { onboardingPreferences.setOnboardingDone(true) },
            )
        }
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
    }
}
