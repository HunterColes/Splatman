package com.huntercoles.splatman.capture.recording

import android.content.Context
import android.hardware.SensorManager
import android.os.HandlerThread
import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** The foss build's [PoseFreeRecorder] (fills `PoseFreeRecorderModule`'s Optional); a fresh one per view model. */
@Module
@InstallIn(ViewModelComponent::class)
object FossRecordingModule {
    @Provides
    fun poseFreeRecorder(
        @ApplicationContext context: Context,
        @DefaultDispatcher worker: CoroutineDispatcher,
        @IoDispatcher io: CoroutineDispatcher,
    ): PoseFreeRecorder {
        val imuThread = HandlerThread("splat-imu")
        return FossPoseFreeRecorder(
            source = Camera2FrameSource(context),
            imu = ImuRecorder(context.getSystemService(SensorManager::class.java), imuThread),
            scansRoot = File(context.filesDir, SCANS_DIR),
            stagingRoot = File(context.filesDir, RECORDING_STAGING_DIR),
            encoder = BitmapJpegEncoder(),
            worker = worker,
            io = io,
            onRelease = { imuThread.quitSafely() },
        )
    }
}
