package com.huntercoles.splatman.library.data.format

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The header of a Gaussian splat file: enough to tell what a file holds before reading its body.
 *
 * Reads two formats: a 3DGS PLY (`element vertex N` and its float properties) and an SPZ (Niantic's gzip format,
 * a 16-byte header: magic "NGSP", version, point count, SH degree, fractional bits, flags, reserved).
 */
sealed interface SplatFileHeader {
    val splatCount: Int
    val shDegree: Int

    data class Ply(
        val format: String,
        override val splatCount: Int,
        val properties: List<String>,
        /** Byte offset of the first splat record. */
        val bodyOffset: Int,
    ) : SplatFileHeader {
        override val shDegree: Int
            get() = when (properties.count { it.startsWith("f_rest_") }) {
                0 -> 0
                9 -> 1
                24 -> 2
                45 -> 3
                else -> -1
            }
    }

    data class Spz(
        val version: Int,
        override val splatCount: Int,
        override val shDegree: Int,
        val fractionalBits: Int,
        val flags: Int,
    ) : SplatFileHeader

    companion object {
        private const val MAX_PLY_HEADER = 64 * 1024
        private const val SPZ_MAGIC = 0x5053474e // "NGSP" read little-endian

        /** Reads a PLY header from [input]; leaves the stream positioned at the first record. */
        fun readPly(input: InputStream): Ply {
            val lines = mutableListOf<String>()
            var read = 0
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("PLY header has no end_header")
                read++
                if (read > MAX_PLY_HEADER) throw IOException("PLY header too long")
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.clear()
                    lines += text
                    if (text == "end_header") break
                } else {
                    line.append(b.toChar())
                }
            }
            if (lines.first() != "ply") throw IOException("not a PLY file")
            val format = lines.firstOrNull { it.startsWith("format ") }?.split(" ")?.getOrNull(1)
                ?: throw IOException("PLY header has no format line")
            var count: Int? = null
            val properties = mutableListOf<String>()
            var inVertex = false
            for (l in lines) {
                val parts = l.trim().split(Regex("\\s+"))
                when (parts[0]) {
                    "element" -> {
                        inVertex = parts.getOrNull(1) == "vertex"
                        if (inVertex) count = parts.getOrNull(2)?.toIntOrNull()
                    }
                    "property" -> if (inVertex) properties += parts.last()
                }
            }
            return Ply(
                format = format,
                splatCount = count ?: throw IOException("PLY header has no vertex element"),
                properties = properties,
                bodyOffset = read,
            )
        }

        /** Reads an SPZ header from [input] (the raw, gzip-compressed file). */
        fun readSpz(input: InputStream): Spz {
            val data = DataInputStream(GZIPInputStream(input))
            val magic = Integer.reverseBytes(data.readInt())
            if (magic != SPZ_MAGIC) throw IOException("not an SPZ file")
            val version = Integer.reverseBytes(data.readInt())
            val count = Integer.reverseBytes(data.readInt())
            val shDegree = data.readUnsignedByte()
            val fractionalBits = data.readUnsignedByte()
            val flags = data.readUnsignedByte()
            data.readUnsignedByte() // reserved
            return Spz(version, count, shDegree, fractionalBits, flags)
        }
    }
}
