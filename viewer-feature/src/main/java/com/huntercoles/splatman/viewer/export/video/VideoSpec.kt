package com.huntercoles.splatman.viewer.export.video

import kotlin.math.roundToLong

/**
 * The turntable video's encoder settings (#262): a [size] x [size] square, H.264 at [bitrate] bits per second, for
 * [OrbitPath.SECONDS] seconds at [OrbitPath.FPS]. [estimatedBytes] is the Export sheet's size estimate
 * (bitrate x duration).
 */
data class VideoSpec(val size: Int, val bitrate: Int) {
    val estimatedBytes: Long get() = bitrate.toLong() * OrbitPath.SECONDS / BITS_PER_BYTE

    companion object {
        /** The encoder's MIME (H.264). */
        const val MIME = "video/avc"

        /** The file's MIME, for the share sheet and ACTION_CREATE_DOCUMENT. */
        const val FILE_MIME = "video/mp4"

        const val PREFERRED_SIZE = 1080
        const val MIN_SIZE = 144
        const val BITRATE_AT_PREFERRED = 8_000_000
        private const val ALIGN = 16
        private const val BITS_PER_BYTE = 8

        /** The sizes tried, largest first: [PREFERRED_SIZE], then the multiples of 16 below it down to [MIN_SIZE]. */
        fun candidates(): List<Int> {
            val below = (PREFERRED_SIZE - 1) / ALIGN * ALIGN
            return listOf(PREFERRED_SIZE) + (below downTo MIN_SIZE step ALIGN)
        }

        /**
         * The largest square the encoder takes ([supports] a size x size frame), its bitrate scaled by area from
         * [BITRATE_AT_PREFERRED] and passed through [clampBitrate] (the encoder's range); null when none fits.
         */
        fun choose(supports: (Int) -> Boolean, clampBitrate: (Int) -> Int = { it }): VideoSpec? {
            val size = candidates().firstOrNull(supports) ?: return null
            val area = size.toDouble() * size / (PREFERRED_SIZE.toDouble() * PREFERRED_SIZE)
            val bitrate = (BITRATE_AT_PREFERRED * area).roundToLong().toInt()
            return VideoSpec(size, clampBitrate(bitrate))
        }
    }
}
