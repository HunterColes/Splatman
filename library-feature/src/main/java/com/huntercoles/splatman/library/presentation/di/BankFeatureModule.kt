package com.huntercoles.splatman.library.presentation.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.huntercoles.splatman.library.presentation.LibraryNavigationFactory
import com.huntercoles.splatman.library.presentation.BankUiState
import com.huntercoles.splatman.core.navigation.NavigationFactory
import javax.inject.Singleton

@Module
@InstallIn(ViewModelComponent::class)
internal object BankViewModelModule {

    @Provides
    fun provideInitialBankUiState(): BankUiState = BankUiState()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface LibraryFeatureModule {

    @Singleton
    @Binds
    @IntoSet
    fun bindLibraryNavigationFactory(factory: LibraryNavigationFactory): NavigationFactory
}
