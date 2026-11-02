package com.huntercoles.splatman.splat.model

/** One selected camera frame (ADR 0002). Immutable; its images are immutable by contract. */
data class Keyframe(
    /** Index of this keyframe within its capture, from 0. */
    val id: Int,
    /** Exposure time, nanoseconds on the ARCore `Frame.getTimestamp()` clock. */
    val timestampNs: Long,
    /** Intrinsics of [image], pixels of [image]. */
    val intrinsics: CameraIntrinsics,
    /** `T_world_camera` at exposure: camera frame Cgl -> world frame W, metres. */
    val pose: CameraPose,
    /** RGBA8888, row-major, sensor orientation; its size equals [intrinsics]' width x height, pixels. */
    val image: RgbImage,
    /** Depth in metres with its own intrinsics (pixels of the depth image), or null when the device gives none. */
    val depth: DepthImage?,
) {
    init {
        require(image.width == intrinsics.width && image.height == intrinsics.height) {
            "image ${image.width}x${image.height} does not match intrinsics ${intrinsics.width}x${intrinsics.height}"
        }
    }
}
