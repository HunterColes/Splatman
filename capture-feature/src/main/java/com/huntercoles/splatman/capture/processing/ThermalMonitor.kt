package com.huntercoles.splatman.capture.processing

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone is too hot to train: true at `THERMAL_STATUS_SEVERE` or hotter (ADR 0005). */
interface ThermalMonitor {
    val severe: StateFlow<Boolean>
}

/** [ThermalMonitor] on `PowerManager.getCurrentThermalStatus()` (API 29+); never severe on older phones. */
@Singleton
class PowerThermalMonitor @Inject constructor(@ApplicationContext context: Context) : ThermalMonitor {
    private val state = MutableStateFlow(false)
    override val severe: StateFlow<Boolean> = state.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(PowerManager::class.java)
            if (power != null) {
                state.value = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                // App-lifetime singleton: the listener lives as long as the process.
                power.addThermalStatusListener { status -> state.value = status >= PowerManager.THERMAL_STATUS_SEVERE }
            }
        }
    }
}
