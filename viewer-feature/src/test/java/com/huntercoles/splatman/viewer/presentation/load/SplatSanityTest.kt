package com.huntercoles.splatman.viewer.presentation.load

import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import com.huntercoles.splatman.viewer.rendering.ViewerConstants
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SplatSanityTest {

    private fun rejected(cloud: SplatCloud): String =
        assertThrows<SplatFileException> { SplatSanity.requireUsable(cloud) }.message!!

    @Test
    fun `a sound cloud passes`() {
        assertDoesNotThrow { SplatSanity.requireUsable(TestClouds.cloud(10, shDegree = 1)) }
    }

    @Test
    fun `an empty cloud is refused`() {
        assertTrue(rejected(TestClouds.cloud(0)).contains("no splats"))
    }

    @Test
    fun `a value that is not finite is refused, field by field`() {
        val fields: List<(SplatCloud) -> FloatArray> = listOf(
            { it.positions }, { it.logScales }, { it.rotations }, { it.opacityLogits }, { it.shDc }, { it.shRest },
        )
        for ((k, field) in fields.withIndex()) {
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val cloud = TestClouds.cloud(4, shDegree = 1)
                val values = field(cloud)
                values[values.size - 1] = bad
                assertTrue(rejected(cloud).startsWith("The file holds broken splats"), "field $k, $bad")
            }
        }
    }

    @Test
    fun `a centre or a splat beyond the bound is refused`() {
        val far = TestClouds.cloud(4).also { it.positions[7] = -2 * SplatSanity.MAX_COORD }
        assertTrue(rejected(far).contains("km away"))
        val huge = TestClouds.cloud(4).also { it.logScales[2] = 30f }
        assertTrue(rejected(huge).contains("km wide"))
    }

    @Test
    fun `at the bound the orbit camera still has an eye apart from its target`() {
        val c = SplatSanity.MAX_COORD
        val camera = GaussianCameraController()
        camera.setTarget(Vec3(c, -c, c))
        camera.setDistance(ViewerConstants.MIN_DISTANCE)
        repeat(24) {
            assertDoesNotThrow { camera.getViewMatrix() }
            camera.onRotate(37f, 11f)
        }
    }
}
