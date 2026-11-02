package com.huntercoles.splatman.capture.presentation.capture

import com.huntercoles.splatman.capture.tracking.FeaturePoints
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.CameraIntrinsics
import com.huntercoles.splatman.splat.model.CameraPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubjectGeometryTest {
    private val k = CameraIntrinsics(fx = 500f, fy = 500f, cx = 320f, cy = 240f, width = 640, height = 480)
    private val mapping = DisplayMapping(1080f, 2400f, k)
    private val pose = CameraPose(
        Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.7f),
        Vec3(0.2f, 1.4f, 0.5f),
    )

    @Test
    fun `the image centre is the view centre and image x runs down the portrait view`() {
        assertEquals(ScreenPoint(540f, 1200f), mapping.imageToView(320f, 240f))
        // Scale 3.75 (2400 / 640 covers the view): 50 px right in the image is 187.5 px down on the view.
        assertEquals(ScreenPoint(540f, 1387.5f), mapping.imageToView(370f, 240f))
        assertEquals(ScreenPoint(352.5f, 1200f), mapping.imageToView(320f, 290f))
    }

    @Test
    fun `a point along a view ray projects back onto that view point`() {
        for (p in listOf(ScreenPoint(540f, 1200f), ScreenPoint(100f, 300f), ScreenPoint(900f, 2000f))) {
            val world = pose.translationM + mapping.ray(pose, p) * 1.3f
            val back = mapping.project(pose, world)
            assertNotNull(back)
            back!!
            assertEquals(p.x, back.x, 0.05f)
            assertEquals(p.y, back.y, 0.05f)
        }
    }

    @Test
    fun `a point behind the camera does not project`() {
        val behind = pose.translationM - mapping.ray(pose, mapping.centre)
        assertNull(mapping.project(pose, behind))
    }

    private fun points(vararg p: Vec3) = FeaturePoints(
        p.size,
        FloatArray(3 * p.size) { i -> p[i / 3].let { v -> floatArrayOf(v.x, v.y, v.z)[i % 3] } },
        FloatArray(p.size) { 1f },
    )

    @Test
    fun `the subject is the median depth of the feature points near the ray`() {
        val origin = Vec3.ZERO
        val ray = Vec3(0f, 0f, -1f)
        val near = points(
            Vec3(0.01f, 0f, -0.5f),
            Vec3(0f, 0.02f, -0.8f),
            Vec3(0f, 0f, -2f),
            Vec3(1f, 0f, -1f), // 45 degrees off: ignored
            Vec3(0f, 0f, 1f), // behind: ignored
        )
        val s = pickSubject(origin, ray, near)
        assertEquals(-0.8f, s.z, 1e-5f)
        assertEquals(0f, s.x, 1e-5f)
    }

    @Test
    fun `a wider cone is tried before the default distance`() {
        val wide = points(Vec3(0.2f, 0f, -1f)) // 11 degrees off
        assertEquals(-1f, pickSubject(Vec3.ZERO, Vec3(0f, 0f, -1f), wide).z, 1e-5f)
        val none = pickSubject(Vec3.ZERO, Vec3(0f, 0f, -2f), FeaturePoints.EMPTY)
        assertEquals(Vec3(0f, 0f, -DEFAULT_DISTANCE_M), none)
    }
}
