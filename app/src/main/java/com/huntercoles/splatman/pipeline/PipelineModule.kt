package com.huntercoles.splatman.pipeline

import android.content.Context
import com.huntercoles.splatman.capture.presentation.processing.percent
import com.huntercoles.splatman.capture.presentation.processing.stageProgress
import com.huntercoles.splatman.capture.processing.ProcessingListener
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanDeleter
import com.huntercoles.splatman.capture.processing.ScanNames
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.capture.tracking.FlavourTracking
import com.huntercoles.splatman.capture.tracking.TrackingAvailability
import com.huntercoles.splatman.core.coroutines.IoScope
import com.huntercoles.splatman.library.data.repository.ScanStatusSync
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import com.huntercoles.splatman.library.presentation.BuildProgress
import com.huntercoles.splatman.library.presentation.ScanSupport
import com.huntercoles.splatman.viewer.crop.store.ScanChangeListener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** How often a library delete checks that a cancelled run has stopped writing. */
private const val CANCEL_POLL_MS = 20L

/**
 * Wires capture's processing runner to the library: the runner's starts and ends update the scan rows, and a
 * library delete stops the scan's run first.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PipelineModule {

    @Provides
    @Singleton
    fun provideScanStatusSync(scans: ScanRepository, @IoScope scope: CoroutineScope): ScanStatusSync =
        ScanStatusSync(scans, scope)

    @Provides
    @IntoSet
    fun provideLibraryListener(sync: ScanStatusSync): ProcessingListener = ProcessingListener { scanId, state ->
        when (state) {
            is ProcessingState.Running -> sync.started(scanId)
            // NeedsPoses: nothing failed and no splat, so the row is read from the folder again (CAPTURED).
            is ProcessingState.Done, ProcessingState.Cancelled, is ProcessingState.NeedsPoses ->
                sync.finished(scanId, failed = false)
            is ProcessingState.Failed -> sync.finished(scanId, failed = true)
            ProcessingState.Idle, is ProcessingState.Paused -> Unit
        }
    }

    @Provides
    @Singleton
    fun provideProcessingCanceller(repository: ProcessingRepository, folders: ScanFolders): ProcessingCanceller =
        ProcessingCanceller { scanId ->
            val folder = folders.folder(scanId) ?: return@ProcessingCanceller
            repository.cancel(folder)
            while (repository.isRunning(folder)) delay(CANCEL_POLL_MS)
        }

    /** A building card's percent: the processing runner's state of the scan's folder, as Processing shows it. */
    @Provides
    fun provideBuildProgress(repository: ProcessingRepository, folders: ScanFolders): BuildProgress =
        BuildProgress { scanId ->
            val folder = folders.folder(scanId) ?: return@BuildProgress flowOf(null)
            // state() reads the folder the first time; that stays off the main thread.
            flow { emitAll(repository.state(folder)) }.map { it.stageProgress()?.percent() }.flowOn(Dispatchers.IO)
        }

    /** The Library dims its Scan FAB when the flavour's tracker says this phone cannot track motion. */
    @Provides
    fun provideScanSupport(@ApplicationContext context: Context): ScanSupport {
        val tracker = FlavourTracking.support(context)
        return ScanSupport { tracker.check().availability != TrackingAvailability.UNSUPPORTED }
    }

    /** A crop Apply or Revert changed the scan's splat.ply: the Library recounts it. */
    @Provides
    fun provideScanChangeListener(scans: ScanRepository): ScanChangeListener =
        ScanChangeListener { scanId -> scans.refresh(scanId) }

    @Provides
    fun provideScanNames(scans: ScanRepository): ScanNames = ScanNames { scanId -> scans.get(scanId)?.name }

    @Provides
    fun provideScanDeleter(scans: ScanRepository): ScanDeleter = ScanDeleter { scanId -> scans.delete(scanId) }
}
