package com.huntercoles.splatman.capture.processing

import android.content.Context
import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.train.Trainer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Processing runner wiring (ADR 0005): `CpuTrainer` unless the debug flag in [TrainerChoice] asks for the seed only. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProcessingModule {
    @Binds
    abstract fun bindDecoder(decoder: BitmapKeyframeDecoder): KeyframeDecoder

    @Binds
    abstract fun bindThermal(monitor: PowerThermalMonitor): ThermalMonitor

    companion object {
        @Provides
        fun provideTrainer(@ApplicationContext context: Context): Trainer = TrainerChoice.trainer(
            context.getSharedPreferences(TrainerChoice.PREFS, Context.MODE_PRIVATE)
                .getBoolean(TrainerChoice.PREF_SEED_TRAINER, false),
        )

        @Provides
        fun provideTrainingConfig(): TrainingConfig = TrainingConfig()
    }
}
