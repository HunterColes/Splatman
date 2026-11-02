package com.huntercoles.splatman.core.navigation

import com.huntercoles.splatman.core.preferences.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellRoutesTest {

    @Test
    fun `first run starts at onboarding`() {
        assertEquals(NavigationDestination.Onboarding, ShellRoutes.startDestination(onboardingDone = false))
    }

    @Test
    fun `after onboarding the app starts at the library`() {
        assertEquals(NavigationDestination.Library, ShellRoutes.startDestination(onboardingDone = true))
    }

    @Test
    fun `parses routes without arguments`() {
        assertEquals(NavigationDestination.Onboarding, ShellRoutes.parse("onboarding"))
        assertEquals(NavigationDestination.Library, ShellRoutes.parse("library"))
        assertEquals(NavigationDestination.Capture, ShellRoutes.parse("capture"))
        assertEquals(NavigationDestination.Settings, ShellRoutes.parse("settings"))
        assertEquals(NavigationDestination.About, ShellRoutes.parse("/about/"))
    }

    @Test
    fun `parses the scan id of processing and viewer`() {
        assertEquals(NavigationDestination.Processing("scan-42"), ShellRoutes.parse("processing/scan-42"))
        assertEquals(NavigationDestination.Viewer("abc"), ShellRoutes.parse("viewer/abc"))
    }

    @Test
    fun `rejects a missing or blank scan id`() {
        assertNull(ShellRoutes.parse("viewer"))
        assertNull(ShellRoutes.parse("viewer/"))
        assertNull(ShellRoutes.parse("processing/ "))
    }

    @Test
    fun `rejects unknown routes and extra segments`() {
        assertNull(ShellRoutes.parse(""))
        assertNull(ShellRoutes.parse("tools"))
        assertNull(ShellRoutes.parse("library/x"))
        assertNull(ShellRoutes.parse("viewer/a/b"))
        assertNull(ShellRoutes.parse("settings/abc"))
    }

    @Test
    fun `every route round-trips through its path`() {
        val all = listOf(
            NavigationDestination.Onboarding,
            NavigationDestination.Library,
            NavigationDestination.Capture,
            NavigationDestination.Processing("p1"),
            NavigationDestination.Viewer("v1"),
            NavigationDestination.Settings,
            NavigationDestination.About,
        )
        all.forEach { destination ->
            val path = ShellRoutes.path(destination)!!
            assertEquals(destination, ShellRoutes.parse(path), path)
        }
        assertEquals("viewer/v1", ShellRoutes.path(NavigationDestination.Viewer("v1")))
        assertNull(ShellRoutes.path(NavigationDestination.Back))
    }

    @Test
    fun `capture, processing and viewer are always dark`() {
        listOf(
            NavigationDestination.Capture,
            NavigationDestination.Processing("p"),
            NavigationDestination.Viewer("v"),
        ).forEach {
            assertTrue(ShellRoutes.isDark(it, ThemeMode.Light, systemDark = false))
        }
    }

    @Test
    fun `other screens follow the theme setting, then the system`() {
        val library = NavigationDestination.Library
        assertFalse(ShellRoutes.isDark(library, ThemeMode.System, systemDark = false))
        assertTrue(ShellRoutes.isDark(library, ThemeMode.System, systemDark = true))
        assertTrue(ShellRoutes.isDark(library, ThemeMode.Dark, systemDark = false))
        assertFalse(ShellRoutes.isDark(NavigationDestination.Settings, ThemeMode.Light, systemDark = true))
    }
}
