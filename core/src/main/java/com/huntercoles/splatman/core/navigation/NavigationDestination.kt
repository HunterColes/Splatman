package com.huntercoles.splatman.core.navigation

import kotlinx.serialization.Serializable

sealed class NavigationDestination {
    @Serializable
    data object Viewer : NavigationDestination()

    @Serializable
    data object Library : NavigationDestination()

    @Serializable
    data object Tools : NavigationDestination()

    @Serializable
    data object Back : NavigationDestination()
}
