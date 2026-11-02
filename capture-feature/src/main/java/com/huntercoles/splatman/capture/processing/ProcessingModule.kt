package com.huntercoles.splatman.capture.processing

import com.huntercoles.splatman.splat.packet.TrainingConfig
import com.huntercoles.splatman.splat.train.SeedTrainer
import com.huntercoles.splatman.splat.train.Trainer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Processing runner wiring (ADR 0005): stage 1 only for now; #44 swaps in `CpuTrainer`. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProcessingModule {
    @Binds
    abstract fun bindDecoder(decoder: BitmapKeyframeDecoder): KeyframeDecoder

    @Binds
    abstract fun bindThermal(monitor: PowerThermalMonitor): ThermalMonitor

    companion object {
        @Provides
        fun provideTrainer(): Trainer = SeedTrainer()

        @Provides
        fun provideTrainingConfig(): TrainingConfig = TrainingConfig()
    }
}
