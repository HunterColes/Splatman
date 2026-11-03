package com.huntercoles.splatman.capture.recording

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.huntercoles.splatman.splat.io.bundle.ImuCsvWriter
import com.huntercoles.splatman.splat.io.bundle.ImuSensor
import com.huntercoles.splatman.splat.math.Vec3
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** What [ImuRecorder.start] could register. An empty [missing] means both sensors record. */
data class ImuStart(
    /** Sensors the phone does not have (or would not register); their rows are absent from `imu.csv`. */
    val missing: Set<ImuSensor>,
) {
    /** True when at least one sensor records. */
    val recording: Boolean get() = missing.size < ImuSensor.entries.size
}

/**
 * Records `TYPE_GYROSCOPE` and `TYPE_ACCELEROMETER` into `imu.csv` (ADR 0007) at a 10 000 us period (100 Hz),
 * delivered on [handlerThread]. Rows carry `SensorEvent.timestamp`, the sensor's own clock. 100 Hz is under the
 * 200 Hz cap, so no `HIGH_SAMPLING_RATE_SENSORS` permission is needed.
 *
 * A writer failure (disk full) never escapes the sensor thread: the recorder unregisters itself and keeps the error
 * in [failure]. The caller owns the writer: [stop] first, then close it.
 */
class ImuRecorder internal constructor(
    private val sensorManager: SensorManager,
    private val handlerThread: HandlerThread,
    private val handlerFor: (HandlerThread) -> Handler?,
) {
    constructor(sensorManager: SensorManager, handlerThread: HandlerThread) :
        this(sensorManager, handlerThread, { Handler(it.looper) })

    @Volatile private var writer: ImuCsvWriter? = null

    @Volatile private var gravity: Vec3? = null

    @Volatile private var error: IOException? = null

    private val gyroListeners = CopyOnWriteArrayList<(Long, Float, Float, Float) -> Unit>()

    /** Low-passed accelerometer (m/s^2, device axes), null until the first accelerometer sample. */
    val latestGravity: Vec3? get() = gravity

    /** The write error that stopped the recording, or null. */
    val failure: IOException? get() = error

    /** True between [start] and [stop] (or a write failure). */
    val isRecording: Boolean get() = writer != null

    internal val listener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            val sensor = event.sensor
            if (v == null || v.size < AXES || sensor == null) return
            onSample(sensor.type, event.timestamp, v[0], v[1], v[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Called with `(timestamp_ns, x, y, z)` for every gyroscope sample, rad/s, on the sensor thread. */
    fun addGyroListener(l: (Long, Float, Float, Float) -> Unit) {
        gyroListeners += l
    }

    /** Registers both sensors into [writer]; says which are missing. Throws if already recording. */
    @Synchronized
    fun start(writer: ImuCsvWriter): ImuStart {
        check(this.writer == null) { "ImuRecorder is already recording" }
        if (!handlerThread.isAlive) handlerThread.start()
        val handler = handlerFor(handlerThread)
        error = null
        gravity = null
        this.writer = writer
        val missing = mutableSetOf<ImuSensor>()
        for ((type, imu) in SENSORS) {
            val s = sensorManager.getDefaultSensor(type)
            if (s == null || !sensorManager.registerListener(listener, s, SAMPLING_PERIOD_US, handler)) missing += imu
        }
        val start = ImuStart(missing)
        if (!start.recording) this.writer = null
        Timber.i("ImuRecorder start: missing=%s period_us=%d", missing, SAMPLING_PERIOD_US)
        return start
    }

    /** Unregisters both sensors. Safe to call twice or without [start]. */
    @Synchronized
    fun stop() {
        writer = null
        sensorManager.unregisterListener(listener)
    }

    internal fun onSample(type: Int, timestampNs: Long, x: Float, y: Float, z: Float) {
        val w = writer ?: return
        try {
            when (type) {
                Sensor.TYPE_GYROSCOPE -> {
                    w.gyro(timestampNs, x, y, z)
                    gyroListeners.forEach { it(timestampNs, x, y, z) }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    w.accel(timestampNs, x, y, z)
                    val a = Vec3(x, y, z)
                    val g = gravity
                    gravity = if (g == null) a else g * GRAVITY_ALPHA + a * (1f - GRAVITY_ALPHA)
                }
            }
        } catch (e: IOException) {
            error = e
            Timber.e(e, "ImuRecorder: imu.csv write failed, recording stopped")
            stop()
        } catch (e: IllegalStateException) {
            // The writer was closed while a late sample was in flight: drop it and stay stopped.
            Timber.w(e, "ImuRecorder: sample after the writer closed")
            stop()
        }
    }

    internal companion object {
        /** 100 Hz. */
        const val SAMPLING_PERIOD_US = 10_000
        const val GRAVITY_ALPHA = 0.8f
        private const val AXES = 3
        private val SENSORS = listOf(Sensor.TYPE_GYROSCOPE to ImuSensor.GYRO, Sensor.TYPE_ACCELEROMETER to ImuSensor.ACCEL)
    }
}
