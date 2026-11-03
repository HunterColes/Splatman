package com.huntercoles.splatman.viewer.crop

import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Vec3
import kotlin.math.sqrt

/**
 * Where a face handle of a crop box sits on screen and how a drag on it maps to metres.
 *
 * [x], [y]: the face centre in pixels (origin top-left, y down, as Compose draws). [dirX], [dirY]: the unit screen
 * direction the face's outward normal points to. [pxPerMetre]: how many pixels the face centre moves on screen per
 * metre along that normal, at the face's depth. [draggable] is false when the face is edge-on to the camera
 * ([pxPerMetre] under [CropGestureMath.MIN_PX_PER_METRE]); its direction is then meaningless.
 */
data class FaceHandle(
    val face: BoxFace,
    val x: Float,
    val y: Float,
    val dirX: Float,
    val dirY: Float,
    val pxPerMetre: Float,
    val draggable: Boolean,
)

/**
 * Pure crop gesture maths (ADR 0011): face centres to screen points for a view-projection, and screen drags to face
 * moves. `viewProj` is `proj * view` (column-major [Mat4], world W -> clip); the viewport is [width] x [height] px.
 */
object CropGestureMath {
    /** Below this many screen pixels per metre along its normal a face is edge-on and not draggable. */
    const val MIN_PX_PER_METRE = 8f

    /** The centre of [face] in W. */
    fun faceCentre(box: CropBox, face: BoxFace): Vec3 = box.centre + faceNormal(box, face) * halfExtent(box, face)

    /** The outward unit normal of [face] in W. */
    fun faceNormal(box: CropBox, face: BoxFace): Vec3 {
        val local = when (face) {
            BoxFace.X_NEG -> -Vec3.UNIT_X
            BoxFace.X_POS -> Vec3.UNIT_X
            BoxFace.Y_NEG -> -Vec3.UNIT_Y
            BoxFace.Y_POS -> Vec3.UNIT_Y
            BoxFace.Z_NEG -> -Vec3.UNIT_Z
            BoxFace.Z_POS -> Vec3.UNIT_Z
        }
        return box.rotation.normalized().rotate(local)
    }

    /**
     * The handle of [face] on screen, or null when its centre is behind the camera (or the inputs are not finite).
     * The screen direction is the derivative of the projected centre along the outward normal, so it is exact at
     * the face's depth.
     */
    fun handle(box: CropBox, face: BoxFace, viewProj: Mat4, width: Int, height: Int): FaceHandle? {
        val p = faceCentre(box, face)
        val n = faceNormal(box, face)
        val m = viewProj
        val cx = m[0, 0] * p.x + m[0, 1] * p.y + m[0, 2] * p.z + m[0, 3]
        val cy = m[1, 0] * p.x + m[1, 1] * p.y + m[1, 2] * p.z + m[1, 3]
        val cw = m[3, 0] * p.x + m[3, 1] * p.y + m[3, 2] * p.z + m[3, 3]
        if (!(cw > 0f) || !cx.isFinite() || !cy.isFinite()) return null
        val dx = m[0, 0] * n.x + m[0, 1] * n.y + m[0, 2] * n.z
        val dy = m[1, 0] * n.x + m[1, 1] * n.y + m[1, 2] * n.z
        val dw = m[3, 0] * n.x + m[3, 1] * n.y + m[3, 2] * n.z
        val halfW = width / 2f
        val halfH = height / 2f
        val sx = (cx / cw + 1f) * halfW
        val sy = (1f - cy / cw) * halfH
        // d(ndc)/dt = (dclip * w - clip * dw) / w^2; screen y flips.
        val gx = (dx * cw - cx * dw) / (cw * cw) * halfW
        val gy = -(dy * cw - cy * dw) / (cw * cw) * halfH
        val len = sqrt(gx * gx + gy * gy)
        val draggable = len.isFinite() && len >= MIN_PX_PER_METRE
        return FaceHandle(
            face = face,
            x = sx,
            y = sy,
            dirX = if (draggable) gx / len else 0f,
            dirY = if (draggable) gy / len else 0f,
            pxPerMetre = if (len.isFinite()) len else 0f,
            draggable = draggable,
        )
    }

    /** All six handles in [BoxFace] order; a face behind the camera is left out. */
    fun handles(box: CropBox, viewProj: Mat4, width: Int, height: Int): List<FaceHandle> =
        BoxFace.entries.mapNotNull { handle(box, it, viewProj, width, height) }

    /**
     * Metres to move [handle]'s face along its outward normal for a screen drag of ([dx], [dy]) px: the drag's
     * projection on the handle's direction over [FaceHandle.pxPerMetre]. Positive = out. Null when the face is not
     * draggable or the drag is not finite.
     */
    fun dragMetres(handle: FaceHandle, dx: Float, dy: Float): Float? {
        if (!handle.draggable || !dx.isFinite() || !dy.isFinite()) return null
        val metres = (dx * handle.dirX + dy * handle.dirY) / handle.pxPerMetre
        return metres.takeIf { it.isFinite() }
    }

    /**
     * [box] with [face] moved by a screen drag of ([dx], [dy]) px through [CropBox.moveFace] (opposite face fixed,
     * extent clamped to 1 cm), or null when the face is behind the camera, edge-on, or the drag is not finite (keep
     * the last good box).
     */
    @Suppress("LongParameterList")
    fun drag(box: CropBox, face: BoxFace, viewProj: Mat4, width: Int, height: Int, dx: Float, dy: Float): CropBox? {
        val h = handle(box, face, viewProj, width, height) ?: return null
        val metres = dragMetres(h, dx, dy) ?: return null
        return box.moveFace(face, metres)
    }

    private fun halfExtent(box: CropBox, face: BoxFace): Float = when (face) {
        BoxFace.X_NEG, BoxFace.X_POS -> box.halfExtents.x
        BoxFace.Y_NEG, BoxFace.Y_POS -> box.halfExtents.y
        BoxFace.Z_NEG, BoxFace.Z_POS -> box.halfExtents.z
    }
}
