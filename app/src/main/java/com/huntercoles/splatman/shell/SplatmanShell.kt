package com.huntercoles.splatman.shell

import android.app.Activity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.SplatTheme
import com.huntercoles.splatman.core.design.rememberAnimatorScale
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.NavigationDestination.About
import com.huntercoles.splatman.core.navigation.NavigationDestination.Capture
import com.huntercoles.splatman.core.navigation.NavigationDestination.Library
import com.huntercoles.splatman.core.navigation.NavigationDestination.Onboarding
import com.huntercoles.splatman.core.navigation.NavigationDestination.Processing
import com.huntercoles.splatman.core.navigation.NavigationDestination.Settings
import com.huntercoles.splatman.core.navigation.NavigationDestination.Viewer
import com.huntercoles.splatman.core.navigation.ShellRoutes
import com.huntercoles.splatman.core.preferences.ThemeMode

/** Pixels a screen slides by on a transition: a tenth of the width (shared axis X). */
private const val SLIDE_FRACTION = 10

/**
 * The app shell: no bottom navigation, Library is home, every other screen is a full-screen route.
 * Each route draws in its own theme (Capture, Processing and Viewer always dark); the bars follow the current route.
 */
@Composable
fun SplatmanShell(
    startDestination: NavigationDestination,
    themeMode: ThemeMode,
    requestedRoute: NavigationDestination?,
    onOnboardingDone: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val systemDark = isSystemInDarkTheme()
    val entry by navController.currentBackStackEntryAsState()
    val barsDark = entry?.destination?.isForcedDark() == true || ShellRoutes.isDark(null, themeMode, systemDark)
    SystemBars(dark = barsDark)

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) navController.navigate(requestedRoute)
    }

    val reducedMotion = rememberAnimatorScale() == 0f
    val screen: @Composable (NavigationDestination, @Composable () -> Unit) -> Unit = { destination, content ->
        SplatTheme(
            darkTheme = ShellRoutes.isDark(destination, themeMode, systemDark),
            systemBars = false,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), content = content)
        }
    }

    SplatTheme(darkTheme = barsDark, systemBars = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = { enter(reducedMotion, forward = true) },
                exitTransition = { exit(reducedMotion, forward = true) },
                popEnterTransition = { enter(reducedMotion, forward = false) },
                popExitTransition = { exit(reducedMotion, forward = false) },
            ) {
                composable<Onboarding> {
                    screen(Onboarding) {
                        OnboardingPlaceholder(
                            onDone = {
                                onOnboardingDone()
                                navController.navigate(Library) {
                                    popUpTo<Onboarding> { inclusive = true }
                                }
                            },
                        )
                    }
                }
                composable<Library> {
                    screen(Library) {
                        LibraryHome(
                            onScan = { navController.navigate(Capture) },
                            onSettings = { navController.navigate(Settings) },
                        )
                    }
                }
                composable<Capture> {
                    screen(Capture) { CapturePlaceholder(onBack = { navController.popBackStack() }) }
                }
                composable<Processing> { backStackEntry ->
                    val route = backStackEntry.toRoute<Processing>()
                    screen(route) {
                        ProcessingPlaceholder(scanId = route.scanId, onBack = { navController.popBackStack() })
                    }
                }
                composable<Viewer> { backStackEntry ->
                    val route = backStackEntry.toRoute<Viewer>()
                    screen(route) { ViewerRoute() }
                }
                composable<Settings> {
                    screen(Settings) {
                        SettingsPlaceholder(
                            onBack = { navController.popBackStack() },
                            onAbout = { navController.navigate(About) },
                        )
                    }
                }
                composable<About> {
                    screen(About) { AboutPlaceholder(onBack = { navController.popBackStack() }) }
                }
            }
        }
    }
}

private fun NavDestination.isForcedDark(): Boolean =
    hasRoute<Capture>() || hasRoute<Processing>() || hasRoute<Viewer>()

/** Bar icons light on dark screens and dark on light ones; the bars themselves are transparent (edge-to-edge). */
@Composable
private fun SystemBars(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(
    reducedMotion: Boolean,
    forward: Boolean,
): EnterTransition =
    if (reducedMotion) {
        fadeIn(tween(SplatMotion.ReducedMotionCrossfade))
    } else {
        val spec = tween<Float>(SplatMotion.DurationLong, easing = SplatMotion.EasingEmphasizedDecelerate)
        fadeIn(spec) + slideInHorizontally(
            tween(SplatMotion.DurationLong, easing = SplatMotion.EasingEmphasizedDecelerate),
        ) { width -> if (forward) width / SLIDE_FRACTION else -width / SLIDE_FRACTION }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(
    reducedMotion: Boolean,
    forward: Boolean,
): ExitTransition =
    if (reducedMotion) {
        fadeOut(tween(SplatMotion.ReducedMotionCrossfade))
    } else {
        val spec = tween<Float>(SplatMotion.DurationLong, easing = SplatMotion.EasingEmphasizedAccelerate)
        fadeOut(spec) + slideOutHorizontally(
            tween(SplatMotion.DurationLong, easing = SplatMotion.EasingEmphasizedAccelerate),
        ) { width -> if (forward) -width / SLIDE_FRACTION else width / SLIDE_FRACTION }
    }
