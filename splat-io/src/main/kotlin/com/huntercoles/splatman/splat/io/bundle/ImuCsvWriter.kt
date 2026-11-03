package com.huntercoles.splatman.splat.io.bundle

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

/** The `imu.csv` header (ADR 0007). */
const val IMU_HEADER: String = "timestamp_ns,sensor,x,y,z"

/** An `imu.csv` sensor column value. */
enum class ImuSensor(val code: String) {
    /** `TYPE_GYROSCOPE`, rad/s. */
    GYRO("g"),

    /** `TYPE_ACCELEROMETER`, m/s^2, gravity included. */
    ACCEL("a"),
}

/** One `imu.csv` row, device sensor axes. */
data class ImuSample(val timestampNs: Long, val sensor: ImuSensor, val x: Float, val y: Float, val z: Float)

/**
 * Streams `imu.csv` (ADR 0007) during recording: the file is truncated and the header written once on open, rows
 * are buffered, nothing is kept in memory. Numbers are written with [Float.toString], which is locale-independent
 * (as `Locale.ROOT`) and round-trips every float exactly. [close] flushes and fsyncs.
 */
class ImuCsvWriter(file: File) : Closeable {
    private val stream: FileOutputStream
    private val out: BufferedWriter
    private var closed = false

    init {
        val parent = file.absoluteFile.parentFile
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create ${parent.path}")
        stream = FileOutputStream(file, false)
        out = BufferedWriter(OutputStreamWriter(stream, Charsets.US_ASCII), BUFFER_CHARS)
        out.write(IMU_HEADER)
        out.write("\n")
    }

    /** A `TYPE_GYROSCOPE` sample, rad/s. */
    fun gyro(timestampNs: Long, x: Float, y: Float, z: Float) = row(timestampNs, ImuSensor.GYRO, x, y, z)

    /** A `TYPE_ACCELEROMETER` sample, m/s^2. */
    fun accel(timestampNs: Long, x: Float, y: Float, z: Float) = row(timestampNs, ImuSensor.ACCEL, x, y, z)

    @Synchronized
    private fun row(timestampNs: Long, sensor: ImuSensor, x: Float, y: Float, z: Float) {
        check(!closed) { "ImuCsvWriter is closed" }
        out.write(timestampNs.toString())
        out.write(",")
        out.write(sensor.code)
        out.write(",")
        out.write(x.toString())
        out.write(",")
        out.write(y.toString())
        out.write(",")
        out.write(z.toString())
        out.write("\n")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            out.flush()
            stream.fd.sync()
        } finally {
            out.close()
        }
    }

    private companion object {
        const val BUFFER_CHARS = 64 * 1024
    }
}

/** Reads `imu.csv` back; a bad header or row is rejected naming the file and line. */
object ImuCsvReader {

    @Throws(CaptureBundleException::class)
    fun read(file: File): List<ImuSample> {
        val samples = ArrayList<ImuSample>()
        file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEachIndexed { i, line ->
                if (i == 0) {
                    if (line != IMU_HEADER) throw CaptureBundleException("${file.name}:1: header must be $IMU_HEADER")
                } else if (line.isNotEmpty()) {
                    samples += parse(line) ?: throw CaptureBundleException("${file.name}:${i + 1}: bad row \"$line\"")
                }
            }
        }
        return samples
    }

    private fun parse(line: String): ImuSample? {
        val c = line.split(',')
        if (c.size != 5) return null
        val sensor = ImuSensor.entries.firstOrNull { it.code == c[1] } ?: return null
        val t = c[0].toLongOrNull() ?: return null
        val x = c[2].toFloatOrNull() ?: return null
        val y = c[3].toFloatOrNull() ?: return null
        val z = c[4].toFloatOrNull() ?: return null
        return ImuSample(t, sensor, x, y, z)
    }
}
