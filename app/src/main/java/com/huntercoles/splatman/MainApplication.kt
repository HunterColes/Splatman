package com.huntercoles.splatman

import android.app.Application
import com.huntercoles.splatman.capture.processing.ProcessingRepository
import com.huntercoles.splatman.capture.processing.ScanFolders
import com.huntercoles.splatman.core.coroutines.IoScope
import com.huntercoles.splatman.library.data.repository.ScanStatusSync
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var scanStatusSync: ScanStatusSync

    @Inject
    lateinit var processing: ProcessingRepository

    @Inject
    lateinit var scanFolders: ScanFolders

    @Inject
    @field:IoScope
    lateinit var ioScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }

        // A scan whose run the process died in reads FAILED, never PROCESSING for ever.
        ioScope.launch {
            scanStatusSync.recover { folder -> processing.isRunning(File(scanFolders.root, folder)) }
        }
    }
}
