package com.huntercoles.splatman.viewer.presentation.crop

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.splatDuration
import com.huntercoles.splatman.splat.edit.BoxFace
import com.huntercoles.splatman.splat.edit.CropBox
import com.huntercoles.splatman.splat.math.Mat4
import com.huntercoles.splatman.splat.math.Quat
import com.huntercoles.splatman.splat.math.Vec3
import com.huntercoles.splatman.splat.model.SplatCloud
import com.huntercoles.splatman.viewer.R
import com.huntercoles.splatman.viewer.crop.CropGestureMath
import com.huntercoles.splatman.viewer.rendering.GaussianCameraController
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The six face names (docs/design/screens/crop.md), in chip and focus order. */
enum class CropFace(@StringRes val label: Int, @StringRes val desc: Int) {
    TOP(R.string.crop_face_top, R.string.crop_face_top_desc),
    BOTTOM(R.string.crop_face_bottom, R.string.crop_face_bottom_desc),
    FRONT(R.string.crop_face_front, R.string.crop_face_front_desc),
    BACK(R.string.crop_face_back, R.string.crop_face_back_desc),
    LEFT(R.string.crop_face_left, R.string.crop_face_left_desc),
    RIGHT(R.string.crop_face_right, R.string.crop_face_right_desc),
}

/**
 * The full bounding box of a cloud's centres: no face moves past it, so the box cannot be lost off screen. The UI
 * only makes boxes aligned to W, so the limits are per world axis.
 */
data class CropLimits(val min: Vec3, val max: Vec3) {

    /** How far [face] of [box] may move by [metres] (positive = out), and whether a limit cut the move short. */
    fun clamp(box: CropBox, face: BoxFace, metres: Float): Pair<Float, Boolean> {
        if (!metres.isFinite()) return 0f to false
        val outRoom = outRoom(box, face)
        val inRoom = inRoom(box, face)
        val allowed = metres.coerceIn(-inRoom, outRoom)
        return allowed to (abs(allowed - metres) > EPS)
    }

    /** False when [face] is at the 1 cm minimum extent (In is disabled). */
    fun canMoveIn(box: CropBox, face: BoxFace): Boolean = inRoom(box, face) > EPS

    /** False when [face] is at the cloud's bounding box (Out is disabled). */
    fun canMoveOut(box: CropBox, face: BoxFace): Boolean = outRoom(box, face) > EPS

    private fun outRoom(box: CropBox, face: BoxFace): Float {
        val axis = face.ordinal / 2
        val c = box.centre.at(axis)
        val h = box.halfExtents.at(axis)
        val room = if (face.ordinal % 2 == 1) max.at(axis) - (c + h) else (c - h) - min.at(axis)
        return room.coerceAtLeast(0f)
    }

    private fun inRoom(box: CropBox, face: BoxFace): Float =
        (2f * box.halfExtents.at(face.ordinal / 2) - CropBox.MIN_EXTENT.toFloat()).coerceAtLeast(0f)

    companion object {
        private const val EPS = 1e-4f

        /** The min and max of [cloud]'s centres per axis (a unit box at the origin for an empty cloud). */
        fun of(cloud: SplatCloud): CropLimits {
            if (cloud.count == 0) return CropLimits(Vec3(-1f, -1f, -1f), Vec3(1f, 1f, 1f))
            val lo = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            val hi = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            val p = cloud.positions
            for (i in 0 until cloud.count) {
                for (k in 0 until 3) {
                    val v = p[3 * i + k]
                    if (!v.isFinite()) continue
                    if (v < lo[k]) lo[k] = v
                    if (v > hi[k]) hi[k] = v
                }
            }
            return CropLimits(Vec3(lo[0], lo[1], lo[2]), Vec3(hi[0], hi[1], hi[2]))
        }
    }
}

/** Pure parts of the crop chrome: face names and the size pill's text. */
object CropUi {

    /**
     * Which [BoxFace] each [CropFace] names, from the camera's [initial] orientation (the front three-quarter view):
     * +Y is Top; of the four horizontal axis directions the one nearest the eye direction flattened onto the ground
     * is Front, the one nearest the camera's right is Right. Names do not change as the user orbits.
     */
    fun faceNames(initial: Quat = GaussianCameraController.initialOrientation()): Map<CropFace, BoxFace> {
        val q = initial.normalized()
        val toEye = q.rotate(Vec3.UNIT_Z)
        val right = q.rotate(Vec3.UNIT_X)
        val front = nearestHorizontal(toEye, listOf(BoxFace.X_NEG, BoxFace.X_POS, BoxFace.Z_NEG, BoxFace.Z_POS))
        val side = if (front == BoxFace.X_NEG || front == BoxFace.X_POS) {
            listOf(BoxFace.Z_NEG, BoxFace.Z_POS)
        } else {
            listOf(BoxFace.X_NEG, BoxFace.X_POS)
        }
        val rightFace = nearestHorizontal(right, side)
        return mapOf(
            CropFace.TOP to BoxFace.Y_POS,
            CropFace.BOTTOM to BoxFace.Y_NEG,
            CropFace.FRONT to front,
            CropFace.BACK to opposite(front),
            CropFace.RIGHT to rightFace,
            CropFace.LEFT to opposite(rightFace),
        )
    }

    /** The size pill's length: whole cm under 1 m (`42 cm`), else metres with 2 decimals (`1.20 m`). */
    fun formatLength(metres: Float, locale: Locale = Locale.getDefault()): String {
        val m = if (metres.isFinite()) metres.coerceAtLeast(0f) else 0f
        return if (m < 1f) {
            "${NumberFormat.getIntegerInstance(locale).format((m * CM_PER_M).roundToInt())} cm"
        } else {
            String.format(locale, "%.2f m", m)
        }
    }

    /** Full extents of [box] as (wide, deep, tall): Left to Right, Front to Back, Bottom to Top. */
    fun extents(box: CropBox, names: Map<CropFace, BoxFace>): Triple<Float, Float, Float> {
        fun extent(face: CropFace) = 2f * box.halfExtents.at(names.getValue(face).ordinal / 2)
        return Triple(extent(CropFace.RIGHT), extent(CropFace.FRONT), extent(CropFace.TOP))
    }

    fun opposite(face: BoxFace): BoxFace = BoxFace.entries[face.ordinal xor 1]

    private fun nearestHorizontal(direction: Vec3, candidates: List<BoxFace>): BoxFace =
        candidates.maxBy { face ->
            val sign = if (face.ordinal % 2 == 1) 1f else -1f
            sign * if (face.ordinal / 2 == 0) direction.x else direction.z
        }

    private const val CM_PER_M = 100f
}

private fun Vec3.at(axis: Int): Float = when (axis) {
    0 -> x
    1 -> y
    else -> z
}

/** The camera this frame: world to clip, and the eye in W. */
private class FrameView(val viewProj: Mat4, val eye: Vec3)

/** One face handle this frame: [facingAway] draws it as a ring; [depth] orders overlaps (nearer on top). */
private class HandleView(val face: BoxFace, val x: Float, val y: Float, val facingAway: Boolean, val depth: Float)

/**
 * Crop mode's chrome over the render (docs/design/screens/crop.md): the box edges and face handles drawn from the
 * camera every frame, the top bar with the size pill, and the hint, face picker, nudge and button rows (a side panel
 * on wide or landscape screens). Drags on a handle move its face; touches elsewhere fall through to the Viewer's
 * gestures. While applying, every touch is swallowed and the rows become a progress bar.
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun CropOverlay(
    state: CropUiState,
    cloud: SplatCloud,
    camera: GaussianCameraController,
    haptics: Boolean,
    onIntent: (CropIntent) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val box: CropBox
    val selected: BoxFace
    val applying = state is CropUiState.Applying
    val message: CropMessage?
    when (state) {
        is CropUiState.Editing -> { box = state.box; selected = state.selected; message = null }
        is CropUiState.Applying -> { box = state.box; selected = state.selected; message = null }
        is CropUiState.Refused -> { box = state.box; selected = state.selected; message = state.message }
        is CropUiState.Idle -> return
    }
    val view = LocalView.current
    val limits = remember(cloud) { CropLimits.of(cloud) }
    val names = remember { CropUi.faceNames() }
    val nameOf = remember(names) { names.entries.associate { (k, v) -> v to k } }
    val limitText = stringResource(R.string.crop_limit)
    val currentBox by rememberUpdatedState(box)
    val currentHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(state) {
        if (state is CropUiState.Refused && state.message == CropMessage.EMPTY && currentHaptics) view.reject()
    }

    val nudge = { face: BoxFace, out: Boolean ->
        val (allowed, limited) = limits.clamp(currentBox, face, if (out) NUDGE else -NUDGE)
        if (allowed != 0f) onIntent(CropIntent.MoveFace(face, allowed))
        if (limited) view.announce(limitText)
    }

    var frame by remember { mutableStateOf<FrameView?>(null) }
    LaunchedEffect(camera) {
        while (true) {
            withFrameNanos {
                frame = synchronized(camera) {
                    val eye = camera.getTarget() + camera.getRotation().rotate(Vec3.UNIT_Z) * camera.getDistance()
                    FrameView(camera.getProjectionMatrix() * camera.getViewMatrix(), eye)
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val wide = maxWidth >= WIDE_WIDTH || maxHeight < SHORT_HEIGHT
        val current = frame

        if (current != null) BoxEdges(box, selected, current.viewProj, widthPx, heightPx)

        if (applying) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        } else if (current != null) {
            val handles = handleViews(box, current, widthPx, heightPx).sortedByDescending { it.depth }
            for (h in handles) {
                key(h.face) {
                    FaceHandleNode(
                        handle = h,
                        name = nameOf.getValue(h.face),
                        selected = h.face == selected,
                        camera = camera,
                        offset = with(density) {
                            IntOffset(
                                (h.x - TOUCH.toPx() / 2).roundToInt(),
                                (h.y - TOUCH.toPx() / 2).roundToInt(),
                            )
                        },
                        onSelect = { onIntent(CropIntent.SelectFace(h.face)) },
                        onNudge = { out -> nudge(h.face, out) },
                        onDrag = { dx, dy ->
                            val latest = frame ?: return@FaceHandleNode false
                            val handle = CropGestureMath.handle(currentBox, h.face, latest.viewProj, widthPx, heightPx)
                                ?: return@FaceHandleNode false
                            val metres = CropGestureMath.dragMetres(handle, dx, dy) ?: return@FaceHandleNode false
                            val (allowed, limited) = limits.clamp(currentBox, h.face, metres)
                            if (allowed != 0f) onIntent(CropIntent.MoveFace(h.face, allowed))
                            limited
                        },
                        onLimit = { if (currentHaptics) view.reject() },
                    )
                }
            }
        }

        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            CropPanel(
                box = box,
                selected = selected,
                applying = applying,
                message = message,
                wide = wide,
                names = names,
                limits = limits,
                onSelect = { onIntent(CropIntent.SelectFace(it)) },
                onNudge = nudge,
                onReset = { onIntent(CropIntent.ResetBox) },
                onCancel = { onIntent(CropIntent.Cancel) },
                onApply = onApply,
                modifier = panelModifier,
            )
        }
        val top: @Composable (Modifier) -> Unit = { topModifier ->
            CropTopBar(
                box = box,
                names = names,
                onClose = { if (!applying) onIntent(CropIntent.Cancel) },
                modifier = topModifier,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { top(Modifier.align(Alignment.TopCenter)) }
                panel(
                    Modifier
                        .width(SIDE_PANEL)
                        .fillMaxHeight()
                        .background(SplatColors.OverlayScrim)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Vertical + WindowInsetsSides.End,
                            ),
                        )
                        .padding(SplatDimens.SpacingDefault),
                )
            }
        } else {
            top(Modifier.align(Alignment.TopCenter))
            panel(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SplatColors.OverlayScrim)))
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                    .padding(SplatDimens.SpacingDefault),
            )
        }
    }
}

private fun handleViews(box: CropBox, frame: FrameView, width: Int, height: Int): List<HandleView> =
    CropGestureMath.handles(box, frame.viewProj, width, height).filter { it.draggable }.map { h ->
        val centre = CropGestureMath.faceCentre(box, h.face)
        val normal = CropGestureMath.faceNormal(box, h.face)
        val toEye = frame.eye - centre
        HandleView(h.face, h.x, h.y, facingAway = (normal dot toEye) < 0f, depth = toEye.length())
    }

/** The twelve edges, 2 dp `primary` on a `scrim` outline; the selected face's four edges at 4 dp. */
@Composable
private fun BoxEdges(box: CropBox, selected: BoxFace, viewProj: Mat4, width: Int, height: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim
    Canvas(Modifier.fillMaxSize()) {
        val corners = arrayOfNulls<Offset>(CORNERS)
        for (i in 0 until CORNERS) {
            val p = Vec3(
                box.centre.x + if (i and 1 != 0) box.halfExtents.x else -box.halfExtents.x,
                box.centre.y + if (i and 2 != 0) box.halfExtents.y else -box.halfExtents.y,
                box.centre.z + if (i and 4 != 0) box.halfExtents.z else -box.halfExtents.z,
            )
            corners[i] = project(viewProj, p, width, height)
        }
        val axisBit = 1 shl (selected.ordinal / 2)
        val sideSet = selected.ordinal % 2 == 1
        val thin = 2.dp.toPx()
        val thick = 4.dp.toPx()
        val outline = 1.dp.toPx()
        for (a in 0 until CORNERS) for (bit in intArrayOf(1, 2, 4)) {
            if (a and bit != 0) continue
            val from = corners[a] ?: continue
            val to = corners[a or bit] ?: continue
            val onFace = bit != axisBit && ((a and axisBit != 0) == sideSet)
            val w = if (onFace) thick else thin
            drawLine(scrim, from, to, strokeWidth = w + 2 * outline)
            drawLine(primary, from, to, strokeWidth = w)
        }
    }
}

private fun project(m: Mat4, p: Vec3, width: Int, height: Int): Offset? {
    val cx = m[0, 0] * p.x + m[0, 1] * p.y + m[0, 2] * p.z + m[0, 3]
    val cy = m[1, 0] * p.x + m[1, 1] * p.y + m[1, 2] * p.z + m[1, 3]
    val cw = m[3, 0] * p.x + m[3, 1] * p.y + m[3, 2] * p.z + m[3, 3]
    if (!(cw > 0f)) return null
    val x = (cx / cw + 1f) * width / 2f
    val y = (1f - cy / cw) * height / 2f
    return if (x.isFinite() && y.isFinite()) Offset(x, y) else null
}

@Composable
@Suppress("LongParameterList")
private fun FaceHandleNode(
    handle: HandleView,
    name: CropFace,
    selected: Boolean,
    camera: GaussianCameraController,
    offset: IntOffset,
    onSelect: () -> Unit,
    onNudge: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Boolean,
    onLimit: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val size by animateDpAsState(
        targetValue = if (dragging) DOT_DRAGGING else DOT,
        animationSpec = tween(splatDuration(SplatMotion.DurationShort), easing = SplatMotion.EasingStandard),
        label = "handle",
    )
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onDrag)
    val limit by rememberUpdatedState(onLimit)
    val desc = stringResource(name.desc)
    val inLabel = stringResource(R.string.crop_in_desc, desc)
    val outLabel = stringResource(R.string.crop_out_desc, desc)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        modifier = Modifier
            .offset { offset }
            .size(TOUCH)
            .semantics {
                contentDescription = desc
                this.selected = selected
                onClick { select(); true }
                customActions = listOf(
                    CustomAccessibilityAction(inLabel) { onNudge(false); true },
                    CustomAccessibilityAction(outLabel) { onNudge(true); true },
                )
            }
            .pointerInput(handle.face) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    synchronized(camera) { camera.stopMotion() }
                    select()
                    dragging = true
                    var buzzed = false
                    drag(down.id) { change ->
                        val d = change.positionChange()
                        change.consume()
                        if (move(d.x, d.y) && !buzzed) {
                            buzzed = true
                            limit()
                        }
                    }
                    dragging = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val ring = 2.dp.toPx()
            if (handle.facingAway) {
                drawCircle(scrim, radius = r, style = Stroke(ring + 2.dp.toPx()))
                drawCircle(primary, radius = r, style = Stroke(ring))
            } else {
                drawCircle(scrim, radius = r)
                drawCircle(primary, radius = r - ring)
            }
            if (selected) drawCircle(onPrimary, radius = 2.dp.toPx())
        }
    }
}

@Composable
private fun CropTopBar(box: CropBox, names: Map<CropFace, BoxFace>, onClose: () -> Unit, modifier: Modifier) {
    val (wide, deep, tall) = CropUi.extents(box, names)
    val w = CropUi.formatLength(wide)
    val d = CropUi.formatLength(deep)
    val t = CropUi.formatLength(tall)
    val text = stringResource(R.string.crop_size, w, d, t)
    val desc = stringResource(R.string.crop_size_desc, w, d, t)
    var spoken by remember { mutableStateOf(desc) }
    var spokenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(desc) {
        val wait = ANNOUNCE_MS - (System.currentTimeMillis() - spokenAt)
        if (wait > 0) delay(wait)
        spoken = desc
        spokenAt = System.currentTimeMillis()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(SplatColors.OverlayScrim, Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(SplatDimens.SpacingXSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.crop_cancel),
                    tint = SplatColors.OverlayOnScrim,
                )
            }
            Text(
                text = stringResource(R.string.crop_title),
                style = MaterialTheme.typography.titleLarge,
                color = SplatColors.OverlayOnScrim,
                modifier = Modifier.padding(horizontal = SplatDimens.SpacingSmall),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = SplatColors.OverlayOnScrim,
            modifier = Modifier
                .background(SplatColors.OverlayScrim, RoundedCornerShape(SplatDimens.CornerSmall))
                .padding(horizontal = SplatDimens.SpacingMedium, vertical = SplatDimens.SpacingXSmall)
                .semantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun CropPanel(
    box: CropBox,
    selected: BoxFace,
    applying: Boolean,
    message: CropMessage?,
    wide: Boolean,
    names: Map<CropFace, BoxFace>,
    limits: CropLimits,
    onSelect: (BoxFace) -> Unit,
    onNudge: (BoxFace, Boolean) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
        Text(
            text = stringResource(R.string.crop_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = SplatColors.OverlayOnScrim,
        )
        if (message != null && !applying) {
            Text(
                text = stringResource(if (message == CropMessage.EMPTY) R.string.crop_empty else R.string.crop_error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(SplatDimens.CornerSmall))
                    .padding(SplatDimens.SpacingSmall)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (applying) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                text = stringResource(R.string.crop_applying),
                color = SplatColors.OverlayOnScrim,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        FacePicker(selected = selected, wide = wide, names = names, onSelect = onSelect)
        val selectedName = names.entries.first { it.value == selected }.key
        val faceDesc = stringResource(selectedName.desc)
        Row(horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
            NudgeButton(
                label = stringResource(R.string.crop_in),
                description = stringResource(R.string.crop_in_desc, faceDesc),
                icon = Icons.Rounded.Remove,
                enabled = limits.canMoveIn(box, selected),
                onStep = { onNudge(selected, false) },
                modifier = Modifier.weight(1f),
            )
            NudgeButton(
                label = stringResource(R.string.crop_out),
                description = stringResource(R.string.crop_out_desc, faceDesc),
                icon = Icons.Rounded.Add,
                enabled = limits.canMoveOut(box, selected),
                onStep = { onNudge(selected, true) },
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = TOUCH)) {
                Text(stringResource(R.string.crop_reset))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = TOUCH)) {
                    Text(stringResource(R.string.crop_cancel))
                }
                Button(onClick = onApply, modifier = Modifier.heightIn(min = TOUCH)) {
                    Text(stringResource(R.string.crop_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacePicker(selected: BoxFace, wide: Boolean, names: Map<CropFace, BoxFace>, onSelect: (BoxFace) -> Unit) {
    val group = stringResource(R.string.crop_face_picker)
    val bottomFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { bottomFocus.requestFocus() } }
    val chip: @Composable (CropFace) -> Unit = { name ->
        val face = names.getValue(name)
        val isSelected = face == selected
        FilterChip(
            selected = isSelected,
            onClick = { onSelect(face) },
            label = { Text(stringResource(name.label)) },
            leadingIcon = if (isSelected) {
                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier
                .heightIn(min = TOUCH)
                .then(if (name == CropFace.BOTTOM) Modifier.focusRequester(bottomFocus) else Modifier),
        )
    }
    val groupModifier = Modifier.semantics {
        contentDescription = group
        isTraversalGroup = true
    }
    if (wide) {
        FlowRow(
            modifier = groupModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) { CropFace.entries.forEach { chip(it) } }
    } else {
        val list = rememberLazyListState()
        val index = CropFace.entries.indexOfFirst { names[it] == selected }
        LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem(index) }
        LazyRow(
            state = list,
            modifier = groupModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            itemsIndexed(CropFace.entries) { _, name -> chip(name) }
        }
    }
}

/** A tonal In / Out button: one step per tap; held (touch, Enter or Space) it repeats at the key-repeat rate. */
@Composable
@Suppress("LongParameterList")
private fun NudgeButton(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onStep: () -> Unit,
    modifier: Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        delay(ViewConfiguration.getKeyRepeatTimeout().toLong())
        while (true) {
            repeated = true
            step()
            delay(ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }
    FilledTonalButton(
        onClick = {
            if (!repeated) step()
            repeated = false
        },
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier
            .heightIn(min = TOUCH)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

@Suppress("DEPRECATION")
private fun View.announce(text: String) = announceForAccessibility(text)

private fun View.reject() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

private const val CORNERS = 8
private const val NUDGE = CropViewModel.NUDGE_STEP_METRES
private const val ANNOUNCE_MS = 1000L
private val TOUCH = SplatDimens.TouchTarget
private val DOT = 24.dp
private val DOT_DRAGGING = 32.dp
private val SIDE_PANEL = 360.dp
private val WIDE_WIDTH = 600.dp
private val SHORT_HEIGHT = 480.dp
