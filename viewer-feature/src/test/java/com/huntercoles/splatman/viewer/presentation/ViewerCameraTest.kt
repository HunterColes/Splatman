package com.huntercoles.splatman.viewer.presentation

import androidx.compose.ui.unit.IntSize
import com.huntercoles.splatman.splat.io.SplatFormat
import com.huntercoles.splatman.viewer.presentation.load.LoadedScene
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import com.huntercoles.splatman.viewer.rendering.gl.RenderScene
import com.huntercoles.splatman.viewer.rendering.gl.SceneFraming
import com.huntercoles.splatman.viewer.rendering.pack.SplatPacker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** crop.md: rotating mid-edit keeps the camera pose; only a new scene re-frames. */
class ViewerCameraTest {

    private fun scene(count: Int = 3): LoadedScene {
        val cloud = TestClouds.cloud(count)
        val framing = SceneFraming.of(cloud.positions, cloud.count, 60f)
        return LoadedScene(
            "s", SplatFormat.PLY, cloud, RenderScene(SplatPacker().pack(cloud), cloud.positions, framing.radius),
            framing, shDropped = false,
        )
    }

    @Test
    fun `framing the same scene again after a rotation keeps the pose`() {
        val camera = ViewerCamera()
        val s = scene()
        assertTrue(camera.frame(s, IntSize(1080, 2400)))
        camera.controller.onRotate(120f, 40f)
        camera.controller.zoomStep(2)
        camera.controller.onPan(30f, 10f)
        val rotation = camera.controller.getRotation()
        val distance = camera.controller.getDistance()
        val target = camera.controller.getTarget()

        assertFalse(camera.frame(s, IntSize(2400, 1080)))

        assertEquals(rotation, camera.controller.getRotation())
        assertEquals(distance, camera.controller.getDistance())
        assertEquals(target, camera.controller.getTarget())
    }

    @Test
    fun `a new scene is framed afresh`() {
        val camera = ViewerCamera()
        camera.frame(scene(), IntSize(1080, 2400))
        camera.controller.zoomStep(3)
        val zoomed = camera.controller.getDistance()

        assertTrue(camera.frame(scene(), IntSize(1080, 2400)))
        assertNotEquals(zoomed, camera.controller.getDistance())
    }
}
