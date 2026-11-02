package com.huntercoles.splatman.capture.processing

import android.content.Context
import com.huntercoles.splatman.capture.recording.SCANS_DIR
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.train.Trainer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.io.File

/** Processing runner wiring (ADR 0005): `CpuTrainer` unless the debug flag in [TrainerChoice] asks for the seed only. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProcessingModule {
    @Binds
    abstract fun bindDecoder(decoder: BitmapKeyframeDecoder): KeyframeDecoder

    @Binds
    abstract fun bindThermal(monitor: PowerThermalMonitor): ThermalMonitor

    @Binds
    abstract fun bindNotificationAsk(ask: PrefsNotificationAsk): NotificationAsk

    /** Empty unless the app adds one (it keeps the library's rows in step). */
    @Multibinds
    abstract fun listeners(): Set<ProcessingListener>

    companion object {
        @Provides
        fun provideTrainer(@ApplicationContext context: Context): Trainer = TrainerChoice.trainer(
            context.getSharedPreferences(TrainerChoice.PREFS, Context.MODE_PRIVATE)
                .getBoolean(TrainerChoice.PREF_SEED_TRAINER, false),
        )

        @Provides
        fun provideFreeSpace(): FreeSpace = FileSystemFreeSpace()

        @Provides
        fun provideTrainingConfig(): TrainingConfig = TrainingConfig()

        @Provides
        fun provideScanFolders(@ApplicationContext context: Context): ScanFolders =
            ScanFolders(File(context.filesDir, SCANS_DIR))

        @Provides
        fun provideStarter(@ApplicationContext context: Context): ProcessingStarter =
            ProcessingStarter { scan -> ProcessingService.start(context, scan) }
    }
}
