package com.huntercoles.splatman.viewer.crop

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sqrt

class CropGestureMathTest {

    // Camera at (0, 0, 5) looking at the origin, +Y up, 90 degree vertical field of view on a 1000 x 1000 viewport:
    // a point at depth d and offset o lands 500 * o / d px from the centre.
    private val viewProj = Mat4.perspective((PI / 2).toFloat(), 1f, 0.1f, 100f) *
        Mat4.lookAt(Vec3(0f, 0f, 5f), Vec3(0f, 0f, 0f), Vec3.UNIT_Y)
    private val size = 1000
    private val unit = CropBox(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY)

    private fun handle(face: BoxFace, box: CropBox = unit) =
        requireNotNull(CropGestureMath.handle(box, face, viewProj, size, size)) { "$face is behind the camera" }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertEquals(expected, actual, 1e-3f, what)

    @Test
    fun `face centres and outward directions for a known camera`() {
        val right = handle(BoxFace.X_POS)
        assertNear(600f, right.x, "x+ x")
        assertNear(500f, right.y, "x+ y")
        assertNear(1f, right.dirX, "x+ points right")
        assertNear(0f, right.dirY, "x+ dir y")
        assertNear(100f, right.pxPerMetre, "500 px / 5 m")
        assertTrue(right.draggable)

        val left = handle(BoxFace.X_NEG)
        assertNear(400f, left.x, "x- x")
        assertNear(-1f, left.dirX, "x- points left")

        val top = handle(BoxFace.Y_POS)
        assertNear(500f, top.x, "y+ x")
        assertNear(400f, top.y, "y+ is above the centre (screen y down)")
        assertNear(-1f, top.dirY, "y+ points up the screen")
        assertNear(100f, top.pxPerMetre, "y+ scale")

        val bottom = handle(BoxFace.Y_NEG)
        assertNear(600f, bottom.y, "y- y")
        assertNear(1f, bottom.dirY, "y- points down the screen")

        assertEquals(BoxFace.entries, CropGestureMath.handles(unit, viewProj, size, size).map { it.face })
    }

    @Test
    fun `the scale is the one at the face's depth`() {
        // An off-centre face: the x+ face of a box at x = 2 sits at depth 5, 3 m right: 800 px.
        val box = CropBox(Vec3(2f, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        val h = handle(BoxFace.X_POS, box)
        assertNear(800f, h.x, "x")
        assertNear(100f, h.pxPerMetre, "x moves 100 px per metre at depth 5")
        // The z+ face of the same box is off axis, so moving it toward the camera moves it outward on screen.
        val front = handle(BoxFace.Z_POS, box)
        assertNear(500f + 500f * 2f / 4f, front.x, "z+ centre (2, 0, 1) at depth 4")
        assertTrue(front.draggable)
        assertNear(1f, front.dirX, "toward the camera = away from the screen centre")
        assertNear(500f * 2f / 16f, front.pxPerMetre, "d(500 * 2 / d) / dd at d = 4")
    }

    @Test
    fun `a drag along the direction moves the face out, against it in, scaled to metres`() {
        val out = CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, 50f, 0f)!!
        assertNear(1.25f, out.halfExtents.x, "half after +0.5 m")
        assertNear(0.25f, out.centre.x, "opposite face stays at -1")

        val inward = CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, -50f, 0f)!!
        assertNear(0.75f, inward.halfExtents.x, "half after -0.5 m")
        assertNear(-0.25f, inward.centre.x, "centre")

        val up = CropGestureMath.drag(unit, BoxFace.Y_POS, viewProj, size, size, 0f, -30f)!!
        assertNear(1.15f, up.halfExtents.y, "dragging up moves the top face out")

        val across = CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, 0f, 80f)!!
        assertEquals(unit, across, "a drag across the direction does not move the face")

        val slanted = handle(BoxFace.X_POS)
        assertNear(0.3f, CropGestureMath.dragMetres(slanted, 30f, 40f)!!, "only the projection counts")
    }

    @Test
    fun `a face seen head-on or edge-on is not draggable`() {
        for (face in listOf(BoxFace.Z_POS, BoxFace.Z_NEG)) {
            val h = handle(face)
            assertNear(500f, h.x, "$face x")
            assertNear(500f, h.y, "$face y")
            assertFalse(h.draggable, "$face moves along the view ray: 0 px per metre")
            assertNull(CropGestureMath.dragMetres(h, 10f, 10f))
            assertNull(CropGestureMath.drag(unit, face, viewProj, size, size, 10f, 10f))
        }
        // Far away, a face moves under 8 px per metre: at depth 100, 500 px / 100 m = 5 px per metre.
        val far = CropBox(Vec3(0f, 0f, -94f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        val h = handle(BoxFace.X_POS, far)
        assertTrue(h.pxPerMetre < CropGestureMath.MIN_PX_PER_METRE, "${h.pxPerMetre}")
        assertFalse(h.draggable)
    }

    @Test
    fun `a drag past the opposite face is clamped to 1 cm by CropBox moveFace`() {
        val squashed = CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, -1000f, 0f)!!
        assertNear(0.005f, squashed.halfExtents.x, "1 cm extent")
        assertNear(-0.995f, squashed.centre.x, "x- face stays at -1")
        assertEquals(unit.moveFace(BoxFace.X_POS, -10f), squashed)
    }

    @Test
    fun `a face behind the camera or a bad drag gives nothing`() {
        val behind = CropBox(Vec3(0f, 0f, 10f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        assertNull(CropGestureMath.handle(behind, BoxFace.X_POS, viewProj, size, size))
        assertTrue(CropGestureMath.handles(behind, viewProj, size, size).isEmpty())
        assertNull(CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, Float.NaN, 0f))
        assertNull(CropGestureMath.drag(unit, BoxFace.X_POS, viewProj, size, size, Float.POSITIVE_INFINITY, 0f))
        val nanBox = CropBox(Vec3(Float.NaN, 0f, 0f), Vec3(1f, 1f, 1f), Quat.IDENTITY)
        assertNull(CropGestureMath.handle(nanBox, BoxFace.X_POS, viewProj, size, size))
    }

    @Test
    fun `a rotated box uses its own axes`() {
        // 90 degrees about +Y: local x -> W -z, local z -> W +x.
        val s = sqrt(0.5f)
        val box = CropBox(Vec3(0f, 0f, 0f), Vec3(2f, 1f, 1f), Quat(s, 0f, s, 0f))
        val c = CropGestureMath.faceCentre(box, BoxFace.X_POS)
        assertNear(0f, c.x, "x")
        assertNear(-2f, c.z, "local x+ face is 2 m down -z")
        val n = CropGestureMath.faceNormal(box, BoxFace.Z_POS)
        assertNear(1f, n.x, "local z+ points along W +x")
        val h = handle(BoxFace.Z_POS, box)
        assertNear(600f, h.x, "z+ face centre at (1, 0, 0)")
        assertNear(1f, h.dirX, "z+ face points right")
    }
}
