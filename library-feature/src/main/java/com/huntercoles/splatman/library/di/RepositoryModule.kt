package com.huntercoles.splatman.library.di

import com.huntercoles.splatman.library.data.format.PlyFormatHandler
import com.huntercoles.splatman.library.data.format.SplatFormatHandler
import com.huntercoles.splatman.library.data.local.SplatSceneDao
import com.huntercoles.splatman.library.data.repository.SplatSceneRepositoryImpl
import com.huntercoles.splatman.library.data.storage.SplatStorageManager
import com.huntercoles.splatman.library.domain.repository.SplatSceneRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing repository and data layer dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    @Singleton
    fun providePlyFormatHandler(): PlyFormatHandler {
        return PlyFormatHandler()
    }
    
    @Provides
    @Singleton
    fun provideSplatFormatHandler(): SplatFormatHandler {
        return SplatFormatHandler()
    }
    
    @Provides
    @Singleton
    fun provideSplatSceneRepository(
        dao: SplatSceneDao,
        storageManager: SplatStorageManager,
        plyHandler: PlyFormatHandler,
        splatHandler: SplatFormatHandler
    ): SplatSceneRepository {
        return SplatSceneRepositoryImpl(
            dao = dao,
            storageManager = storageManager,
            plyHandler = plyHandler,
            splatHandler = splatHandler
        )
    }
}
