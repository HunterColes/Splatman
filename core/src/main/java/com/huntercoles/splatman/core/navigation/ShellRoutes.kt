package com.huntercoles.splatman.core.navigation

import com.huntercoles.splatman.core.preferences.ThemeMode

/** The shell's rules: where the app starts, how routes read as paths, and which screens are dark. */
object ShellRoutes {

    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val CAPTURE = "capture"
    const val PROCESSING = "processing"
    const val VIEWER = "viewer"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    /** Onboarding on first run, else the Library (home). */
    fun startDestination(onboardingDone: Boolean): NavigationDestination =
        if (onboardingDone) NavigationDestination.Library else NavigationDestination.Onboarding()

    /**
     * The path of [destination] (`viewer/{scanId}`; `onboarding/{page}` past page 0), or null for
     * [NavigationDestination.Back].
     */
    fun path(destination: NavigationDestination): String? = when (destination) {
        is NavigationDestination.Onboarding ->
            if (destination.page == 0) ONBOARDING else "$ONBOARDING/${destination.page}"
        NavigationDestination.Library -> LIBRARY
        NavigationDestination.Capture -> CAPTURE
        is NavigationDestination.Processing -> "$PROCESSING/${destination.scanId}"
        is NavigationDestination.Viewer -> "$VIEWER/${destination.scanId}"
        NavigationDestination.Settings -> SETTINGS
        NavigationDestination.About -> ABOUT
        NavigationDestination.Back -> null
    }

    /** Parses a route path such as `processing/abc`; null when the route is unknown or its argument is missing. */
    fun parse(path: String): NavigationDestination? {
        val parts = path.trim().trim('/').split('/')
        val name = parts.first()
        val arg = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            parts.size == 1 -> when (name) {
                ONBOARDING -> NavigationDestination.Onboarding()
                LIBRARY -> NavigationDestination.Library
                CAPTURE -> NavigationDestination.Capture
                SETTINGS -> NavigationDestination.Settings
                ABOUT -> NavigationDestination.About
                else -> null
            }
            parts.size == 2 && arg != null -> when (name) {
                PROCESSING -> NavigationDestination.Processing(arg)
                VIEWER -> NavigationDestination.Viewer(arg)
                ONBOARDING -> arg.toIntOrNull()?.takeIf { it >= 0 }?.let { NavigationDestination.Onboarding(it) }
                else -> null
            }
            else -> null
        }
    }

    /** Capture, Processing and Viewer are always dark. */
    fun isForcedDark(destination: NavigationDestination?): Boolean = when (destination) {
        NavigationDestination.Capture, is NavigationDestination.Processing, is NavigationDestination.Viewer -> true
        else -> false
    }

    /** Whether [destination] draws dark: forced, else the Theme setting, else the system. */
    fun isDark(destination: NavigationDestination?, mode: ThemeMode, systemDark: Boolean): Boolean =
        isForcedDark(destination) || when (mode) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> systemDark
        }
}
