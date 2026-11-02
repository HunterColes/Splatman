package com.huntercoles.splatman.splat.io.ply

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** PLY scalar types by byte size; the names follow the PLY spec and its sized aliases. */
internal enum class PlyType(val size: Int) {
    INT8(1), UINT8(1), INT16(2), UINT16(2), INT32(4), UINT32(4), FLOAT32(4), FLOAT64(8);

    companion object {
        fun of(name: String): PlyType? = when (name) {
            "char", "int8" -> INT8
            "uchar", "uint8" -> UINT8
            "short", "int16" -> INT16
            "ushort", "uint16" -> UINT16
            "int", "int32" -> INT32
            "uint", "uint32" -> UINT32
            "float", "float32" -> FLOAT32
            "double", "float64" -> FLOAT64
            else -> null
        }
    }
}

/** One property; [offset] is its byte offset within a row, meaningless once an element has a list property. */
internal class PlyProperty(val name: String, val type: PlyType, val isList: Boolean, val offset: Int)

internal class PlyElement(val name: String, val count: Long, val properties: List<PlyProperty>) {
    val hasList: Boolean get() = properties.any { it.isList }
    val stride: Int get() = properties.sumOf { it.type.size }
}

/** A parsed binary little-endian PLY header; the stream is left at the first body byte. */
internal class PlyHeader(val elements: List<PlyElement>) {

    companion object {
        private const val MAX_HEADER_BYTES = 1 shl 20
        private val WHITESPACE = Regex("\\s+")

        fun parse(input: InputStream): PlyHeader {
            val reader = LineReader(input)
            if (reader.line() != "ply") throw PlyFormatException("not a PLY file: the first line is not 'ply'")
            var formatSeen = false
            val elements = mutableListOf<PlyElement>()
            var name: String? = null
            var count = 0L
            var props = mutableListOf<PlyProperty>()
            var offset = 0
            fun closeElement() {
                name?.let { elements += PlyElement(it, count, props) }
            }
            while (true) {
                val line = reader.line().trim()
                if (line.isEmpty()) continue
                val t = line.split(WHITESPACE)
                when (t[0]) {
                    "format" -> {
                        when (t.getOrNull(1)) {
                            "binary_little_endian" -> Unit
                            "ascii" -> throw PlyFormatException(
                                "ASCII PLY is not supported; save the file as binary_little_endian",
                            )
                            "binary_big_endian" -> throw PlyFormatException(
                                "big-endian PLY is not supported; save the file as binary_little_endian",
                            )
                            else -> throw PlyFormatException("unknown PLY format line: '$line'")
                        }
                        formatSeen = true
                    }
                    "comment", "obj_info" -> Unit
                    "element" -> {
                        if (t.size != 3) throw PlyFormatException("bad element line: '$line'")
                        closeElement()
                        name = t[1]
                        count = t[2].toLongOrNull()?.takeIf { it >= 0 }
                            ?: throw PlyFormatException("bad element count in '$line'")
                        props = mutableListOf()
                        offset = 0
                    }
                    "property" -> {
                        if (name == null) throw PlyFormatException("property before any element: '$line'")
                        val isList = t.getOrNull(1) == "list"
                        if (t.size != if (isList) 5 else 3) throw PlyFormatException("bad property line: '$line'")
                        val typeName = if (isList) t[3] else t[1]
                        val type = PlyType.of(typeName)
                            ?: throw PlyFormatException("unknown property type '$typeName' in '$line'")
                        if (isList && PlyType.of(t[2]) == null) {
                            throw PlyFormatException("unknown list count type '${t[2]}' in '$line'")
                        }
                        val propName = t.last()
                        if (props.any { it.name == propName }) {
                            throw PlyFormatException("property '$propName' appears twice in element '$name'")
                        }
                        props += PlyProperty(propName, type, isList, offset)
                        offset += type.size
                    }
                    "end_header" -> {
                        closeElement()
                        if (!formatSeen) throw PlyFormatException("PLY header has no format line")
                        return PlyHeader(elements)
                    }
                    else -> throw PlyFormatException("unknown PLY header line: '$line'")
                }
            }
        }
    }

    /** Reads ASCII lines byte by byte (the caller buffers the stream), at most [MAX_HEADER_BYTES] in all. */
    private class LineReader(private val input: InputStream) {
        private var total = 0

        fun line(): String {
            val bytes = ByteArrayOutputStream(64)
            while (true) {
                val b = input.read()
                if (b < 0) throw PlyFormatException("PLY header ends before end_header")
                if (++total > MAX_HEADER_BYTES) {
                    throw PlyFormatException("PLY header longer than $MAX_HEADER_BYTES bytes")
                }
                if (b == '\n'.code) break
                bytes.write(b)
            }
            return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        }
    }
}
