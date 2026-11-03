package com.huntercoles.splatman.capture.recording

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * `Optional<PoseFreeRecorder>` for the capture view model: present in the foss build (`FossRecordingModule` binds
 * it), empty in full, which records through ARCore.
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class PoseFreeRecorderModule {
    @BindsOptionalOf
    abstract fun poseFreeRecorder(): PoseFreeRecorder
}
