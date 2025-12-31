package com.huntercoles.splatman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import com.huntercoles.splatman.core.design.SplatmanTheme
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.navigation.NavigationDestination
import com.huntercoles.splatman.core.navigation.bottomNavigationItems
import com.huntercoles.splatman.library.LibraryFeature
import com.huntercoles.splatman.viewer.ViewerFeature

/**
 * Main entry point for Splatman app.
 * This is a clean slate - splat functionality ready to be added.
 * Ready for ARCore capture, Gaussian splatting, and 3D viewer features.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SplatmanTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    var selectedDestination by remember { mutableStateOf<NavigationDestination>(NavigationDestination.Library) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = SplatColors.DarkPurple,
                contentColor = SplatColors.CardWhite
            ) {
                bottomNavigationItems.forEach { item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = stringResource(item.label)
                            )
                        },
                        label = {
                            Text(stringResource(item.label))
                        },
                        selected = selectedDestination == item.destination,
                        onClick = {
                            selectedDestination = item.destination
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SplatColors.SplatGold,
                            selectedTextColor = SplatColors.SplatGold,
                            unselectedIconColor = SplatColors.CardWhite,
                            unselectedTextColor = SplatColors.CardWhite,
                            indicatorColor = SplatColors.MediumPurple
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedDestination) {
            NavigationDestination.Viewer -> ViewerFeature(
                modifier = Modifier.padding(paddingValues)
            )
            NavigationDestination.Library -> LibraryFeature(
                modifier = Modifier.padding(paddingValues)
            )
            NavigationDestination.Tools -> ToolsScreen(
                modifier = Modifier.padding(paddingValues)
            )
            else -> {}
        }
    }
}

@Composable
private fun ToolsScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = SplatColors.SplatBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🔧 Tools",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = SplatColors.AccentPurple
            )
            Text(
                text = "ARCore Capture & Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Coming Soon: Video capture with 6DOF tracking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp)
            )
        }
    }
}
