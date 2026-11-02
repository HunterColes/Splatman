package com.huntercoles.splatman.library.di

import android.content.Context
import androidx.room.Room
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.library.data.local.LibraryDatabase
import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import com.huntercoles.splatman.library.data.sample.AssetSampleRepository
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

/** Folder under the app's files dir that holds one sub-folder per scan (ADR 0007). */
const val SCANS_DIR = "scans"

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun provideLibraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .fallbackToDestructiveMigrationFrom(LibraryDatabase.OLD_POINT_CLOUD_VERSION)
            .build()

    @Provides
    fun provideScanDao(database: LibraryDatabase): ScanDao = database.scanDao()

    /** Nothing processes yet; the processing runner (#43) replaces this binding with one that stops its job. */
    @Provides
    @Singleton
    fun provideProcessingCanceller(): ProcessingCanceller = ProcessingCanceller { }

    @Provides
    @Singleton
    fun provideScanRepository(
        @ApplicationContext context: Context,
        dao: ScanDao,
        canceller: ProcessingCanceller,
        @IoDispatcher io: CoroutineDispatcher,
    ): ScanRepository = ScanRepositoryImpl(dao, File(context.filesDir, SCANS_DIR), canceller, io)

    @Provides
    @Singleton
    fun provideSampleRepository(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): SampleRepository = AssetSampleRepository(context.assets, io)
}
