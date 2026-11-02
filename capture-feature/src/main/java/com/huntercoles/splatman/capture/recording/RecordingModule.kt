package com.huntercoles.splatman.capture.recording

import android.content.Context
import com.huntercoles.splatman.core.coroutines.DefaultDispatcher
import com.huntercoles.splatman.core.coroutines.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** The library's scan folders under `filesDir` (the library feature's `SCANS_DIR`; the library adopts new ones). */
const val SCANS_DIR = "scans"

/** Where a bundle is written before it moves into [SCANS_DIR]; same file system, so the move is a rename. */
const val RECORDING_STAGING_DIR = "recording"

/** A fresh recorder for every capture view model (unscoped on purpose: each one is released with its owner). */
@Module
@InstallIn(ViewModelComponent::class)
object RecordingModule {
    @Provides
    fun captureRecorder(
        @ApplicationContext context: Context,
        @DefaultDispatcher worker: CoroutineDispatcher,
        @IoDispatcher io: CoroutineDispatcher,
    ): CaptureRecorder = CaptureRecorder(
        scansRoot = File(context.filesDir, SCANS_DIR),
        stagingRoot = File(context.filesDir, RECORDING_STAGING_DIR),
        encoder = BitmapJpegEncoder(),
        worker = worker,
        io = io,
    )
}
