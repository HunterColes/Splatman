package com.huntercoles.splatman.pipeline

import com.huntercoles.splatman.capture.processing.ProcessingListener
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ProcessingState
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.core.coroutines.IoScope
import com.huntercoles.splatman.library.data.repository.ScanStatusSync
import com.huntercoles.splatman.library.domain.repository.ProcessingCanceller
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
            is ProcessingState.Done, ProcessingState.Cancelled -> sync.finished(scanId, failed = false)
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
}
