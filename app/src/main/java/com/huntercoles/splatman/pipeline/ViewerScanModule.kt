package com.huntercoles.splatman.pipeline

import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import com.huntercoles.splatman.library.domain.repository.ScanRepository
import com.huntercoles.splatman.library.presentation.PendingDeletes
import com.huntercoles.splatman.viewer.presentation.ScanRecord
import com.huntercoles.splatman.viewer.presentation.ViewerScanStore
import com.huntercoles.splatman.viewer.presentation.load.SplatSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/** Gives the viewer the Library's scans: the name, date and frames for its details, and rename and delete (deferred
 * through the Library's [PendingDeletes], so the Library pops up with Undo). */
@Module
@InstallIn(SingletonComponent::class)
internal object ViewerScanModule {

    @Provides
    fun provideViewerScanStore(
        scans: ScanRepository,
        folders: ScanFolders,
        pendingDeletes: PendingDeletes,
        @IoDispatcher io: CoroutineDispatcher,
    ): ViewerScanStore = object : ViewerScanStore {
        override suspend fun get(scanId: String): ScanRecord? {
            val scan = scans.get(scanId) ?: return null
            val size = withContext(io) {
                folders.folder(scan.folder)?.let { File(it, SplatSource.Scan.SPLAT_FILE) }
                    ?.takeIf { it.isFile }?.length()
            }
            return ScanRecord(scan.name, scan.createdAtMs, scan.keyframeCount, size)
        }

        override suspend fun rename(scanId: String, name: String) = scans.rename(scanId, name)

        override suspend fun delete(scanId: String) = pendingDeletes.defer(setOf(scanId))
    }
}
