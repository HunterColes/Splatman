package com.huntercoles.splatman.capture.recording

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.huntercoles.splatman.splat.io.bundle.ImuCsvReader
import com.huntercoles.splatman.splat.io.bundle.ImuCsvWriter
import com.huntercoles.splatman.splat.io.bundle.ImuSample
import com.huntercoles.splatman.splat.io.bundle.ImuSensor
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** [ImuRecorder] against a fake sensor stream: no device, a mocked SensorManager and hand-built SensorEvents. */
class ImuRecorderTest {
    @TempDir lateinit var dir: File

    private val gyro = sensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensor(Sensor.TYPE_ACCELEROMETER)

    private fun sensor(type: Int): Sensor = mockk { every { this@mockk.type } returns type }

    private fun manager(hasGyro: Boolean = true): SensorManager = mockk(relaxed = true) {
        every { getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns if (hasGyro) gyro else null
        every { getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns accel
        every { registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Handler>()) } returns true
    }

    private fun recorder(sm: SensorManager) = ImuRecorder(sm, mockk<HandlerThread>(relaxed = true)) { null }

    /** A SensorEvent as the framework would deliver it (its constructor is hidden, its fields are public). */
    private fun event(sensor: Sensor, t: Long, x: Float, y: Float, z: Float): SensorEvent {
        val e = mockk<SensorEvent>()
        set(e, "values", floatArrayOf(x, y, z))
        set(e, "sensor", sensor)
        set(e, "timestamp", t)
        return e
    }

    private fun set(target: Any, name: String, value: Any) {
        SensorEvent::class.java.getField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test
    fun registersBothSensorsAt100HzOnTheGivenThread() {
        val sm = manager()
        val start = recorder(sm).start(mockk(relaxed = true))
        assertEquals(emptySet<ImuSensor>(), start.missing)
        verify { sm.registerListener(any<SensorEventListener>(), gyro, ImuRecorder.SAMPLING_PERIOD_US, null as Handler?) }
        verify { sm.registerListener(any<SensorEventListener>(), accel, ImuRecorder.SAMPLING_PERIOD_US, null as Handler?) }
        assertEquals(10_000, ImuRecorder.SAMPLING_PERIOD_US)
    }

    @Test
    fun samplesReachTheWriterInOrderWithTheirOwnTimestamps() {
        val sm = manager()
        val rec = recorder(sm)
        val file = File(dir, "imu.csv")
        val gyroSeen = mutableListOf<Long>()
        rec.addGyroListener { t, _, _, _ -> gyroSeen += t }
        ImuCsvWriter(file).use { w ->
            rec.start(w)
            rec.listener.onSensorChanged(event(gyro, 1_000L, 0.1f, 0.2f, 0.3f))
            rec.listener.onSensorChanged(event(accel, 1_500L, 0.0f, 9.8f, 0.1f))
            rec.listener.onSensorChanged(event(gyro, 11_000L, 0.4f, 0.5f, 0.6f))
            rec.listener.onSensorChanged(event(accel, 10_900L, 0.2f, 9.7f, 0.0f))
            rec.stop()
        }
        assertEquals(
            listOf(
                ImuSample(1_000L, ImuSensor.GYRO, 0.1f, 0.2f, 0.3f),
                ImuSample(1_500L, ImuSensor.ACCEL, 0.0f, 9.8f, 0.1f),
                ImuSample(11_000L, ImuSensor.GYRO, 0.4f, 0.5f, 0.6f),
                ImuSample(10_900L, ImuSensor.ACCEL, 0.2f, 9.7f, 0.0f),
            ),
            ImuCsvReader.read(file),
        )
        assertEquals(listOf(1_000L, 11_000L), gyroSeen)
        assertNotNull(rec.latestGravity)
    }

    @Test
    fun stopUnregistersBothListenersAndIsSafeTwice() {
        val sm = manager()
        val rec = recorder(sm)
        val w = mockk<ImuCsvWriter>(relaxed = true)
        rec.start(w)
        rec.stop()
        rec.stop()
        verify(atLeast = 1) { sm.unregisterListener(rec.listener) }
        assertFalse(rec.isRecording)
        rec.listener.onSensorChanged(event(gyro, 5L, 1f, 1f, 1f))
        verify(exactly = 0) { w.gyro(any(), any(), any(), any()) }
    }

    @Test
    fun stopWithoutStartIsSafe() {
        val sm = manager()
        recorder(sm).stop()
    }

    @Test
    fun aPhoneWithoutAGyroscopeRecordsAccelOnlyAndSaysSo() {
        val sm = manager(hasGyro = false)
        val rec = recorder(sm)
        val w = mockk<ImuCsvWriter>(relaxed = true)
        val start = rec.start(w)
        assertEquals(setOf(ImuSensor.GYRO), start.missing)
        assertTrue(start.recording)
        verify(exactly = 0) { sm.registerListener(any<SensorEventListener>(), gyro, any<Int>(), any<Handler>()) }
        rec.listener.onSensorChanged(event(accel, 7L, 0f, 9.8f, 0f))
        verify { w.accel(7L, 0f, 9.8f, 0f) }
    }

    @Test
    fun aWriteFailureStopsTheRecordingAndIsReportedWithoutThrowing() {
        val sm = manager()
        val rec = recorder(sm)
        val disk = IOException("No space left on device")
        val w = mockk<ImuCsvWriter> {
            every { gyro(1L, any(), any(), any()) } just runs
            every { gyro(2L, any(), any(), any()) } throws disk
        }
        rec.start(w)
        rec.listener.onSensorChanged(event(gyro, 1L, 0f, 0f, 0f))
        rec.listener.onSensorChanged(event(gyro, 2L, 0f, 0f, 0f))
        rec.listener.onSensorChanged(event(gyro, 3L, 0f, 0f, 0f))
        assertSame(disk, rec.failure)
        assertFalse(rec.isRecording)
        verify { sm.unregisterListener(rec.listener) }
        verify(exactly = 0) { w.gyro(3L, any(), any(), any()) }
    }

    @Test
    fun noGravityBeforeTheFirstAccelerometerSample() {
        val rec = recorder(manager())
        rec.start(mockk(relaxed = true))
        assertNull(rec.latestGravity)
    }
}
