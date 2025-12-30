package com.huntercoles.splatman.library.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.huntercoles.splatman.library.presentation.composable.BankRoute
import com.huntercoles.splatman.core.navigation.NavigationDestination.Library
import com.huntercoles.splatman.core.navigation.NavigationFactory
import javax.inject.Inject

class LibraryNavigationFactory @Inject constructor() : NavigationFactory {

    override fun create(builder: NavGraphBuilder) {
        builder.composable<Library> {
            BankRoute()
        }
    }
}
