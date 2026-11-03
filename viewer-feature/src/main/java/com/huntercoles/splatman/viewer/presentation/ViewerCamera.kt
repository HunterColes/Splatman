package com.huntercoles.splatman.viewer.presentation

import androidx.compose.ui.unit.IntSize
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.RoomStart
import kotlin.math.max

/**
 * The viewer's camera, held by [ViewerViewModel] so its pose outlives a rotation or resize (crop.md, "Rotation,
 * resize, process"). A scene is framed once: framing the same scene again (the screen recomposing after a
 * configuration change) keeps the pose the user left.
 */
class ViewerCamera {
    val controller = GaussianCameraController()
    private var framed: LoadedScene? = null

    /**
     * Frames [scene] in the initial view for a screen of [size] unless it is already framed; true if it framed.
     * [keepPose] (a crop reload) adopts the scene but leaves the camera where it is. [room]: a room scan opens from
     * inside at that pose, kept within the scene's framing sphere (docs/design/screens/viewer.md "Room scans").
     */
    fun frame(
        scene: LoadedScene,
        size: IntSize,
        keepPose: Boolean = false,
        room: RoomStart? = null,
    ): Boolean = synchronized(controller) {
        if (framed === scene) return false
        framed = scene
        if (keepPose) return false
        val framing = scene.framing
        controller.reset()
        if (size.width > 0 && size.height > 0) controller.setAspectRatio(size.width.toFloat() / size.height)
        controller.setInitialView(framing.target, framing.radius)
        if (room != null) controller.setRoomView(room, framing.target, framing.radius)
        controller.setFarPlane(max(FAR_PLANE_MIN, (controller.getMaxDistance() + framing.radius) * FAR_PLANE_FACTOR))
        true
    }

    private companion object {
        const val FAR_PLANE_MIN = 100f
        const val FAR_PLANE_FACTOR = 10f
    }
}
