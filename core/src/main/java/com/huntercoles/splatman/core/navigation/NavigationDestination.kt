package com.huntercoles.splatman.core.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's typed routes (docs/design/README.md). The serial names make Navigation's route patterns read
 * `onboarding`, `library`, `capture`, `processing/{scanId}`, `viewer/{scanId}`, `settings` and `about`.
 * [Back] is a command, not a route.
 */
sealed class NavigationDestination {
    @Serializable
    @SerialName("onboarding")
    data object Onboarding : NavigationDestination()

    @Serializable
    @SerialName("library")
    data object Library : NavigationDestination()

    @Serializable
    @SerialName("capture")
    data object Capture : NavigationDestination()

    @Serializable
    @SerialName("processing")
    data class Processing(val scanId: String) : NavigationDestination()

    @Serializable
    @SerialName("viewer")
    data class Viewer(val scanId: String) : NavigationDestination()

    @Serializable
    @SerialName("settings")
    data object Settings : NavigationDestination()

    @Serializable
    @SerialName("about")
    data object About : NavigationDestination()

    @Serializable
    data object Back : NavigationDestination()
}
