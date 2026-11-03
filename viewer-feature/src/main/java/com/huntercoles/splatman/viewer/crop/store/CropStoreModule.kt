package com.huntercoles.splatman.viewer.crop.store

import android.content.Context
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/** The crop store on `filesDir`; app supplies the [ScanChangeListener]. */
@Module
@InstallIn(SingletonComponent::class)
internal object CropStoreModule {
    @Provides
    @Singleton
    fun provideCropStore(
        @ApplicationContext context: Context,
        listener: ScanChangeListener,
        @IoDispatcher io: CoroutineDispatcher,
    ): CropStore = FileCropStore(context.filesDir, listener, io)
}
