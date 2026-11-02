package com.huntercoles.splatman.splat.model

import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3

/**
 * Rigid transform `T_world_camera` (ADR 0002): maps a point in the camera frame (OpenGL camera axes Cgl,
 * ADR 0003) to the world frame W. No frame conversion happens here (ADR 0003: `FrameConversions` only). Immutable.
 */
data class CameraPose(
    /** Rotation camera Cgl -> world W, Hamilton unit quaternion w, x, y, z. */
    val rotation: Quat,
    /** Camera centre in the world frame W, metres. */
    val translationM: Vec3,
)
