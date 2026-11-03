package com.huntercoles.splatman.viewer.presentation.crop

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.presentation.load.TestClouds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** The pure parts of the crop chrome (#186): face names, the size pill, the bounding-box and 1 cm limits. */
class CropOverlayTest {

    private val box = CropBox(Vec3(0f, 0.5f, 0f), Vec3(0.2f, 0.5f, 0.3f), Quat(1f, 0f, 0f, 0f))
    private val limits = CropLimits(Vec3(-1f, 0f, -1f), Vec3(1f, 1f, 1f))

    @Test
    fun `faces are named from the initial front three-quarter view`() {
        val names = CropUi.faceNames()
        assertEquals(BoxFace.Y_POS, names[CropFace.TOP])
        assertEquals(BoxFace.Y_NEG, names[CropFace.BOTTOM])
        assertEquals(BoxFace.Z_POS, names[CropFace.FRONT])
        assertEquals(BoxFace.Z_NEG, names[CropFace.BACK])
        assertEquals(BoxFace.X_POS, names[CropFace.RIGHT])
        assertEquals(BoxFace.X_NEG, names[CropFace.LEFT])
    }

    @Test
    fun `a camera looking from +X names +X Front and -Z Right`() {
        // Yaw 90 degrees about +Y: the eye sits on +X, the camera's right points to -Z.
        val s = Math.sqrt(0.5).toFloat()
        val names = CropUi.faceNames(Quat(s, 0f, s, 0f))
        assertEquals(BoxFace.X_POS, names[CropFace.FRONT])
        assertEquals(BoxFace.X_NEG, names[CropFace.BACK])
        assertEquals(BoxFace.Z_NEG, names[CropFace.RIGHT])
        assertEquals(BoxFace.Z_POS, names[CropFace.LEFT])
        assertEquals(6, names.values.toSet().size)
    }

    @Test
    fun `the size pill shows whole cm under 1 m and metres with 2 decimals from 1 m`() {
        assertEquals("42 cm", CropUi.formatLength(0.42f, Locale.US))
        assertEquals("1 cm", CropUi.formatLength(0.01f, Locale.US))
        assertEquals("1.00 m", CropUi.formatLength(1f, Locale.US))
        assertEquals("1.20 m", CropUi.formatLength(1.2f, Locale.US))
        assertEquals("0 cm", CropUi.formatLength(Float.NaN, Locale.US))
    }

    @Test
    fun `extents read wide, deep and tall from the named axes`() {
        val (wide, deep, tall) = CropUi.extents(box, CropUi.faceNames())
        assertEquals(0.4f, wide, 1e-6f)
        assertEquals(0.6f, deep, 1e-6f)
        assertEquals(1f, tall, 1e-6f)
    }

    @Test
    fun `a face stops at the cloud's bounding box and the move says it hit the limit`() {
        // X_POS sits at 0.2, the cloud ends at 1.0: 0.8 m of room.
        assertEquals(0.5f to false, limits.clamp(box, BoxFace.X_POS, 0.5f))
        val (allowed, limited) = limits.clamp(box, BoxFace.X_POS, 2f)
        assertEquals(0.8f, allowed, 1e-6f)
        assertTrue(limited)
        // Y_POS is already on the cloud's top: Out is disabled, In is not.
        assertFalse(limits.canMoveOut(box, BoxFace.Y_POS))
        assertTrue(limits.canMoveIn(box, BoxFace.Y_POS))
        assertEquals(0f to true, limits.clamp(box, BoxFace.Y_POS, 0.01f))
    }

    @Test
    fun `a face stops at the 1 cm minimum extent`() {
        val (allowed, limited) = limits.clamp(box, BoxFace.X_NEG, -5f)
        assertEquals(-0.39f, allowed, 1e-6f)
        assertTrue(limited)
        val thin = box.moveFace(BoxFace.X_NEG, allowed)
        assertEquals(0.01f, 2f * thin.halfExtents.x, 1e-5f)
        assertFalse(limits.canMoveIn(thin, BoxFace.X_NEG))
        assertFalse(limits.canMoveIn(thin, BoxFace.X_POS))
        assertTrue(limits.canMoveOut(thin, BoxFace.X_NEG))
    }

    @Test
    fun `the limits are the min and max of the centres and ignore non-finite ones`() {
        val cloud = TestClouds.cloud(4)
        val positions = cloud.positions.copyOf().also {
            it[0] = -2f
            it[4] = 3f
            it[8] = Float.NaN
        }
        val withEdges = SplatCloud(
            count = cloud.count,
            shDegree = cloud.shDegree,
            positions = positions,
            logScales = cloud.logScales,
            rotations = cloud.rotations,
            opacityLogits = cloud.opacityLogits,
            shDc = cloud.shDc,
            shRest = cloud.shRest,
        )
        val l = CropLimits.of(withEdges)
        assertEquals(-2f, l.min.x)
        assertEquals(3f, l.max.y)
        assertTrue(l.max.z.isFinite() && l.min.z.isFinite())
    }

    @Test
    fun `opposite flips the side of the same axis`() {
        assertEquals(BoxFace.X_POS, CropUi.opposite(BoxFace.X_NEG))
        assertEquals(BoxFace.Y_NEG, CropUi.opposite(BoxFace.Y_POS))
        assertEquals(BoxFace.Z_POS, CropUi.opposite(BoxFace.Z_NEG))
    }
}
