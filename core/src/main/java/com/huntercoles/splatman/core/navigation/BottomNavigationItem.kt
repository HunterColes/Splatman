package com.huntercoles.splatman.core.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.huntercoles.splatman.core.R

data class BottomNavigationItem(
    val destination: NavigationDestination,
    val icon: ImageVector,
    @StringRes val label: Int,
)

val bottomNavigationItems = listOf(
    BottomNavigationItem(
        destination = NavigationDestination.Viewer,
        icon = Icons.Filled.VideoLibrary,
        label = R.string.navigation_viewer
    ),
    BottomNavigationItem(
        destination = NavigationDestination.Library,
        icon = Icons.Filled.PhotoLibrary,
        label = R.string.navigation_library
    ),
    BottomNavigationItem(
        destination = NavigationDestination.Tools,
        icon = Icons.Filled.Build,
        label = R.string.navigation_tools
    ),
)
