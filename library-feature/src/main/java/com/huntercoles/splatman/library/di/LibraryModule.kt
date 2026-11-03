package com.huntercoles.splatman.library.di

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.room.Room
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.library.data.local.LibraryDatabase
import com.huntercoles.splatman.library.data.local.MIGRATION_2_3
import com.huntercoles.splatman.library.data.local.MIGRATION_3_4
import com.huntercoles.splatman.library.data.local.ScanDao
import com.huntercoles.splatman.library.data.repository.ScanRepositoryImpl
import com.huntercoles.splatman.library.data.sample.AssetSampleRepository
import com.huntercoles.splatman.library.data.storage.ScanStorage
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.SampleRepository
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import com.huntercoles.splatman.library.importing.AndroidImportSource
import com.huntercoles.splatman.library.importing.Importer
import com.huntercoles.splatman.library.importing.ImportSourceFactory
import com.huntercoles.splatman.library.importing.ImportValidator
import com.huntercoles.splatman.library.importing.SplatImporter
import com.huntercoles.splatman.library.presentation.SizeText
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideScanDao(database: LibraryDatabase): ScanDao = database.scanDao()

    /** [canceller] is bound by the app (it stops the processing runner's job). */
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

    @Provides
    @Singleton
    fun provideScanStorage(@ApplicationContext context: Context): ScanStorage =
        ScanStorage(File(context.filesDir, SCANS_DIR))

    /** Imports land in the scans folder; its free space is checked before the copy. */
    @Provides
    fun provideSplatImporter(
        @ApplicationContext context: Context,
        scans: ScanRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ): Importer {
        val root = File(context.filesDir, SCANS_DIR)
        return SplatImporter(ImportValidator(), scans, freeBytes = { root.mkdirs(); root.usableSpace }, io = io)
    }

    /** A card's folder size, the phone's way ("38 MB"). */
    @Provides
    fun provideSizeText(@ApplicationContext context: Context): SizeText =
        SizeText { bytes -> Formatter.formatShortFileSize(context, bytes) }

    @Provides
    fun provideImportSourceFactory(@ApplicationContext context: Context): ImportSourceFactory =
        ImportSourceFactory { uri -> AndroidImportSource(context.contentResolver, Uri.parse(uri)) }
}
