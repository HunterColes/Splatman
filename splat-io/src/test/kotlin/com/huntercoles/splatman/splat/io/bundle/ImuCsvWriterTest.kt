package com.huntercoles.splatman.splat.io.bundle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale

class ImuCsvWriterTest {

    @TempDir
    lateinit var tmp: File

    private val samples = listOf(
        ImuSample(81234560012345L, ImuSensor.GYRO, 0.0123f, -0.0041f, 0.0007f),
        ImuSample(81234560498765L, ImuSensor.ACCEL, 0.112f, 9.791f, 0.304f),
        ImuSample(81234565012345L, ImuSensor.GYRO, 0.0119f, -0.0044f, 0.0009f),
        ImuSample(81234565500000L, ImuSensor.ACCEL, 1.0E-7f, -123456.79f, Float.MIN_VALUE),
    )

    private fun write(file: File) = ImuCsvWriter(file).use { w ->
        for (s in samples) {
            when (s.sensor) {
                ImuSensor.GYRO -> w.gyro(s.timestampNs, s.x, s.y, s.z)
                ImuSensor.ACCEL -> w.accel(s.timestampNs, s.x, s.y, s.z)
            }
        }
    }

    @Test
    fun `header is the ADR's and rows match its example`() {
        val file = File(tmp, IMU_FILE)
        write(file)
        val lines = file.readLines()
        assertEquals("timestamp_ns,sensor,x,y,z", lines[0])
        assertEquals("81234560012345,g,0.0123,-0.0041,7.0E-4", lines[1])
        assertEquals("81234560498765,a,0.112,9.791,0.304", lines[2])
        assertEquals(1, lines.count { it == IMU_HEADER })
    }

    /** Float precision: exact. Each value is written with Float.toString, which parses back to the same float. */
    @Test
    fun `imu csv parses back to the same samples exactly`() {
        val file = File(tmp, IMU_FILE)
        write(file)
        assertEquals(samples, ImuCsvReader.read(file))
    }

    @Test
    fun `numbers ignore a comma-decimal default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val file = File(tmp, IMU_FILE)
            write(file)
            assertEquals("81234560498765,a,0.112,9.791,0.304", file.readLines()[2])
            assertEquals(samples, ImuCsvReader.read(file))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `reopening truncates and writes the header once`() {
        val file = File(tmp, IMU_FILE)
        write(file)
        ImuCsvWriter(file).use { it.gyro(5L, 1f, 2f, 3f) }
        assertEquals(listOf(IMU_HEADER, "5,g,1.0,2.0,3.0"), file.readLines())
    }

    @Test
    fun `writing after close fails`() {
        val w = ImuCsvWriter(File(tmp, IMU_FILE))
        w.close()
        w.close()
        assertThrows<IllegalStateException> { w.gyro(1L, 0f, 0f, 0f) }
    }

    @Test
    fun `a bad row is rejected naming the file and line`() {
        val file = File(tmp, IMU_FILE)
        file.writeText("$IMU_HEADER\n1,g,0.1,0.2,0.3\n2,m,0.1,0.2,0.3\n")
        val e = assertThrows<CaptureBundleException> { ImuCsvReader.read(file) }
        assertTrue(e.message!!.contains("$IMU_FILE:3"), e.message)
    }
}
