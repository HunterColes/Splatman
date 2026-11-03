package com.huntercoles.splatman

import com.huntercoles.splatman.core.navigation.NavigationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** EXTRA_ROUTE stays pending until the shell has opened it, saved state or not (#279). */
class MainActivityRouteTest {
    @Test
    fun aRouteNotYetOpenedIsStillPendingAfterARecreation() {
        // A recreation before the first frame restores a saved state in which the route was never opened.
        assertEquals(NavigationDestination.About, MainActivity.pendingRoute("about", opened = false))
    }

    @Test
    fun anOpenedRouteIsNotOpenedAgain() {
        assertNull(MainActivity.pendingRoute("about", opened = true))
    }

    @Test
    fun noExtraNoRoute() {
        assertNull(MainActivity.pendingRoute(null, opened = false))
    }
}
