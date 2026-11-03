package com.huntercoles.splatman.splat.io.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk `transforms.json` of a capture bundle (ADR 0007): Nerfstudio's layout plus `splatman_version`.
 * Field names are Nerfstudio's; the `depth_*` intrinsics are Splatman's own (Nerfstudio ignores them).
 */
@Serializable
internal data class TransformsJson(
    @SerialName("camera_model") val cameraModel: String = CAMERA_MODEL,
    /** Missing in a plain Nerfstudio file: read as version 1. */
    @SerialName("splatman_version") val splatmanVersion: Int = 1,
    @SerialName("ply_file_path") val plyFilePath: String? = null,
    /** The capture subject `[x, y, z]`, frame W, metres; absent in older bundles and plain Nerfstudio files. */
    @SerialName("splatman_subject") val subject: List<Float>? = null,
    val frames: List<FrameJson>,
)

@Serializable
internal data class FrameJson(
    @SerialName("file_path") val filePath: String,
    /** `T_W_Cgl`, row-major, 4 rows of 4 (ADR 0003 rule 7). */
    @SerialName("transform_matrix") val transformMatrix: List<List<Float>>,
    @SerialName("fl_x") val flX: Float,
    @SerialName("fl_y") val flY: Float,
    val cx: Float,
    val cy: Float,
    val w: Int,
    val h: Int,
    val k1: Float = 0f,
    val k2: Float = 0f,
    val k3: Float = 0f,
    val p1: Float = 0f,
    val p2: Float = 0f,
    @SerialName("timestamp_ns") val timestampNs: Long = 0L,
    @SerialName("depth_file_path") val depthFilePath: String? = null,
    @SerialName("depth_fl_x") val depthFlX: Float? = null,
    @SerialName("depth_fl_y") val depthFlY: Float? = null,
    @SerialName("depth_cx") val depthCx: Float? = null,
    @SerialName("depth_cy") val depthCy: Float? = null,
    @SerialName("depth_w") val depthW: Int? = null,
    @SerialName("depth_h") val depthH: Int? = null,
)

internal const val CAMERA_MODEL = "OPENCV"
internal const val TRANSFORMS_FILE = "transforms.json"
internal const val SEED_FILE = "seed.ply"

internal val bundleJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
