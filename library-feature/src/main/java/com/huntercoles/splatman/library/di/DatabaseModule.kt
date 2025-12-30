package com.huntercoles.splatman.library.di

import android.content.Context
import androidx.room.Room
import com.huntercoles.splatman.library.data.local.SplatDatabase
import com.huntercoles.splatman.library.data.local.SplatSceneDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing database dependencies for the library-feature module.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideSplatDatabase(
        @ApplicationContext context: Context
    ): SplatDatabase {
        return Room.databaseBuilder(
            context,
            SplatDatabase::class.java,
            SplatDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration() // For MVP - will improve in production
            .build()
    }
    
    @Provides
    @Singleton
    fun provideSplatSceneDao(database: SplatDatabase): SplatSceneDao {
        return database.splatSceneDao()
    }
}
