package com.huntercoles.splatman.splat.io.spz

/**
 * The 16-byte SPZ header after gunzip (Niantic spz `PackedGaussiansHeader`), little-endian: magic `NGSP`
 * (u32 0x5053474e), version u32, count u32, SH degree u8, fractional bits u8, flags u8, reserved u8.
 */
data class SpzHeader(
    /** Format version, 2 or 3 on read; 3 on write. */
    val version: Int,
    /** Number of splats. */
    val count: Int,
    /** Spherical-harmonics degree, 0..3. */
    val shDegree: Int,
    /** Positions are 24-bit signed fixed point with this many fractional bits. */
    val fractionalBits: Int,
    /** Bit field; bit 0 ([SpzCodec.FLAG_ANTIALIASED]) marks a cloud trained with antialiasing. */
    val flags: Int,
) {
    /** True when the antialiased flag is set. */
    val antialiased: Boolean get() = flags and SpzCodec.FLAG_ANTIALIASED != 0
}
