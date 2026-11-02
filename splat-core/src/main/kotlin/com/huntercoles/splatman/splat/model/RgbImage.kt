package com.huntercoles.splatman.splat.model

/**
 * An RGBA8888 image (ADR 0002): row-major from the top-left pixel, sensor orientation. [rgba] is not copied in or
 * out: immutable by contract, no code writes into it once the image is built.
 */
class RgbImage(
    /** Width, pixels. */
    val width: Int,
    /** Height, pixels. */
    val height: Int,
    /** `width * height * 4` bytes, row-major, r, g, b, a per pixel, sRGB 0..255 (read as unsigned). */
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "rgba must hold ${width * height * 4} bytes for ${width}x$height, got ${rgba.size}"
        }
    }
}
