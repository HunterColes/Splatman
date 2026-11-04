package com.huntercoles.splatman.viewer.presentation.erase

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.splatDuration
import com.huntercoles.splatman.viewer.R
import kotlinx.coroutines.delay
import java.text.NumberFormat

/** The brush diameters of docs/design/screens/erase.md ("Brush"); the one place to tune them. */
internal object EraseBrush {
    fun diameter(size: EraseBrushSize): Dp = size.diameterDp.dp

    @StringRes
    fun label(size: EraseBrushSize): Int = when (size) {
        EraseBrushSize.SMALL -> R.string.erase_size_small
        EraseBrushSize.MEDIUM -> R.string.erase_size_medium
        EraseBrushSize.LARGE -> R.string.erase_size_large
    }
}

/**
 * The touch side of erase mode the ViewModel does not hold: the stroke trail and the finger under the brush ring
 * (surface pixels), and whether Remove isolated is running (painting is ignored meanwhile). Lives in the screen.
 */
@Stable
class EraseSession {
    val trail = mutableStateListOf<Offset>()
    var finger by mutableStateOf<Offset?>(null)
        private set

    /** Moves on every new stroke, so the trail's fade restarts. */
    var strokes by mutableIntStateOf(0)
        private set

    var isolating by mutableStateOf(false)

    fun begin(at: Offset) {
        trail.clear()
        trail += at
        finger = at
        strokes++
    }

    fun to(at: Offset) {
        trail += at
        finger = at
    }

    fun end() {
        finger = null
    }
}

/**
 * Splits one pointer gesture in erase mode (docs/design/screens/erase.md "Gestures"): one finger paints from
 * touch-down to lift; a second finger ends the stroke there (marks kept) and the rest of the gesture moves the
 * camera until every finger lifts. A gesture that starts while painting is not allowed paints nothing.
 */
internal class EraseGesture(private val sink: Sink) {
    interface Sink {
        fun start(x: Float, y: Float)
        fun to(x: Float, y: Float)
        fun end()
    }

    private enum class Mode { IDLE, PAINT, CAMERA, IGNORED }

    private var mode = Mode.IDLE

    val painting: Boolean get() = mode == Mode.PAINT

    /**
     * One pointer event: [fingers] pressed, ([x], [y]) the first finger's position; [canPaint] is read only when
     * a gesture starts. Returns true when the event is a two-finger camera move.
     */
    fun event(fingers: Int, x: Float, y: Float, canPaint: Boolean = true): Boolean {
        when (mode) {
            Mode.IDLE -> mode = when {
                fingers >= 2 -> Mode.CAMERA
                fingers == 1 && canPaint -> Mode.PAINT.also { sink.start(x, y) }
                fingers == 1 -> Mode.IGNORED
                else -> Mode.IDLE
            }
            Mode.PAINT -> when {
                fingers >= 2 -> {
                    sink.end()
                    mode = Mode.CAMERA
                }
                fingers == 1 -> sink.to(x, y)
                else -> {
                    sink.end()
                    mode = Mode.IDLE
                }
            }
            Mode.CAMERA, Mode.IGNORED -> if (fingers == 0) mode = Mode.IDLE
        }
        return mode == Mode.CAMERA && fingers >= 2
    }

    /** The gesture was cut (cancel, rotation): a stroke in progress ends there and keeps its marks. */
    fun cancel() {
        if (mode == Mode.PAINT) sink.end()
        mode = Mode.IDLE
    }
}

/** What the erase chrome shows and enables for one state (docs/design/screens/erase.md "States"). */
internal data class EraseControls(
    val sizeEnabled: Boolean,
    val isolatedEnabled: Boolean,
    val undoEnabled: Boolean,
    val cancelEnabled: Boolean,
    val applyEnabled: Boolean,
    val showCount: Boolean,
    val marked: Boolean,
    val applying: Boolean,
    val isolating: Boolean,
    val message: EraseMessage?,
) {
    companion object {
        /** [isolating]: Remove isolated is running; [fingerDown]: a stroke is being painted. */
        fun of(state: EraseUiState, isolating: Boolean, fingerDown: Boolean): EraseControls {
            val applying = state is EraseUiState.Applying
            val running = isolating && state is EraseUiState.Painting
            val free = !applying && !running
            return EraseControls(
                sizeEnabled = free && !fingerDown,
                isolatedEnabled = free,
                undoEnabled = free && state.canUndo,
                cancelEnabled = !applying,
                // ADR 0014 rule 7: nothing marked, nothing to apply; no pass may be in flight.
                applyEnabled = free && state.marked > 0 &&
                    (state is EraseUiState.Marked || state is EraseUiState.Refused),
                showCount = state.marked > 0,
                marked = state.marked > 0,
                applying = applying,
                isolating = running,
                message = (state as? EraseUiState.Refused)?.message,
            )
        }
    }
}

/**
 * Erase mode's chrome over the render (docs/design/screens/erase.md): the brush ring and stroke trail drawn from
 * [session], the top bar with Close and the count pill, and the hint, brush size, Remove isolated and button rows
 * (a side panel on wide or landscape screens). Touches outside the chrome fall through to the render, which paints;
 * while applying every touch is swallowed and the rows become a progress bar. [onClose] is Close (×): the screen
 * asks before dropping marks.
 */
@Composable
@Suppress("LongMethod", "LongParameterList")
fun EraseOverlay(
    state: EraseUiState,
    session: EraseSession,
    haptics: Boolean,
    talkBack: Boolean,
    onIntent: (EraseIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is EraseUiState.Idle) return
    val view = LocalView.current
    val controls = EraseControls.of(state, session.isolating, session.finger != null)
    LaunchedEffect(state) {
        if (state !is EraseUiState.Painting) session.isolating = false
        if (state is EraseUiState.Refused && state.message == EraseMessage.ALL_MARKED && haptics) view.reject()
    }
    var previews by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_WIDTH || maxHeight < SHORT_HEIGHT
        BrushMarks(session = session, brush = state.brush, previews = previews)
        if (controls.applying) {
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
        }
        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            ErasePanel(
                brush = state.brush,
                controls = controls,
                talkBack = talkBack,
                onPick = {
                    onIntent(EraseIntent.SetBrushSize(it))
                    previews++
                },
                onIsolated = {
                    session.isolating = true
                    onIntent(EraseIntent.RemoveIsolated)
                },
                onIntent = onIntent,
                modifier = panelModifier,
            )
        }
        val top: @Composable (Modifier) -> Unit = { topModifier ->
            EraseTopBar(marked = state.marked, show = controls.showCount, onClose = onClose, modifier = topModifier)
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
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
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

/** The trail (fades after lift), the ring under the finger, and the size preview ring after a pick. */
@Composable
private fun BrushMarks(session: EraseSession, brush: EraseBrushSize, previews: Int) {
    val diameter = with(LocalDensity.current) { EraseBrush.diameter(brush).toPx() }
    val ringWidth = with(LocalDensity.current) { RING.toPx() }
    val outline = with(LocalDensity.current) { RING_OUTLINE.toPx() }
    val trailColor = MaterialTheme.colorScheme.primary.copy(alpha = TRAIL_ALPHA)
    val scrim = MaterialTheme.colorScheme.scrim
    val fadeMs = splatDuration(SplatMotion.DurationMedium)
    val showMs = splatDuration(SplatMotion.DurationLong)
    val hideMs = splatDuration(SplatMotion.DurationShort)
    val trailAlpha = remember { Animatable(1f) }
    val previewAlpha = remember { Animatable(0f) }
    val down = session.finger != null
    LaunchedEffect(down, session.strokes) {
        if (down) {
            trailAlpha.snapTo(1f)
        } else {
            trailAlpha.animateTo(0f, tween(fadeMs))
            session.trail.clear()
        }
    }
    LaunchedEffect(previews) {
        if (previews == 0) return@LaunchedEffect
        previewAlpha.snapTo(1f)
        delay(showMs.toLong())
        previewAlpha.animateTo(0f, tween(hideMs))
    }
    Canvas(Modifier.fillMaxSize()) {
        val points = session.trail
        if (points.isNotEmpty() && trailAlpha.value > 0f) {
            val color = trailColor.copy(alpha = trailColor.alpha * trailAlpha.value)
            if (points.size == 1) {
                drawCircle(color, radius = diameter / 2f, center = points[0])
            } else {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(path, color, style = Stroke(width = diameter, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        session.finger?.let { ring(it, diameter / 2f, ringWidth, outline, scrim, 1f) }
        if (previewAlpha.value > 0f) ring(center, diameter / 2f, ringWidth, outline, scrim, previewAlpha.value)
    }
}

/** A 2 dp OverlayOnScrim circle with a 1 dp scrim outline both sides (3:1 on white or black splats). */
@Suppress("LongParameterList")
private fun DrawScope.ring(at: Offset, radius: Float, width: Float, outline: Float, scrim: Color, alpha: Float) {
    drawCircle(scrim.copy(alpha = alpha), radius = radius, center = at, style = Stroke(width + 2f * outline))
    drawCircle(SplatColors.OverlayOnScrim.copy(alpha = alpha), radius = radius, center = at, style = Stroke(width))
}

@Composable
private fun EraseTopBar(marked: Int, show: Boolean, onClose: () -> Unit, modifier: Modifier) {
    val count = NumberFormat.getIntegerInstance().format(marked)
    val text = pluralStringResource(R.plurals.erase_count, marked, count)
    // Polite live region, announced at most once a second while it changes.
    var spoken by remember { mutableStateOf(text) }
    var spokenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(text) {
        val wait = ANNOUNCE_MS - (System.currentTimeMillis() - spokenAt)
        if (wait > 0) delay(wait)
        spoken = text
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
                    contentDescription = stringResource(R.string.erase_close_desc),
                    tint = SplatColors.OverlayOnScrim,
                )
            }
            Text(
                text = stringResource(R.string.erase_title),
                style = MaterialTheme.typography.titleLarge,
                color = SplatColors.OverlayOnScrim,
                modifier = Modifier.padding(horizontal = SplatDimens.SpacingSmall),
            )
        }
        if (show) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                color = SplatColors.OverlayOnScrim,
                maxLines = 1,
                softWrap = false,
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun ErasePanel(
    brush: EraseBrushSize,
    controls: EraseControls,
    talkBack: Boolean,
    onPick: (EraseBrushSize) -> Unit,
    onIsolated: () -> Unit,
    onIntent: (EraseIntent) -> Unit,
    modifier: Modifier,
) {
    val isolatedFocus = remember { FocusRequester() }
    LaunchedEffect(talkBack) { if (talkBack) runCatching { isolatedFocus.requestFocus() } }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
        Text(
            text = stringResource(if (controls.marked) R.string.erase_hint_marked else R.string.erase_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = SplatColors.OverlayOnScrim,
        )
        if (controls.applying) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                text = stringResource(R.string.erase_applying),
                color = SplatColors.OverlayOnScrim,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        BrushPicker(selected = brush, enabled = controls.sizeEnabled, onPick = onPick)
        FilledTonalButton(
            onClick = onIsolated,
            enabled = controls.isolatedEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH)
                .focusRequester(isolatedFocus),
        ) {
            if (controls.isolating) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(if (controls.isolating) R.string.erase_isolated_running else R.string.erase_isolated))
        }
        controls.message?.let { message ->
            Text(
                text = stringResource(if (message == EraseMessage.ALL_MARKED) R.string.erase_all else R.string.erase_error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(SplatDimens.CornerSmall))
                    .padding(SplatDimens.SpacingSmall)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // Undo left, Cancel and Apply right; when they do not fit (large text) Undo moves to its own row above.
        val undoDesc = stringResource(R.string.erase_undo_desc)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            TextButton(
                onClick = { onIntent(EraseIntent.Undo) },
                enabled = controls.undoEnabled,
                modifier = Modifier
                    .heightIn(min = TOUCH)
                    .semantics { contentDescription = undoDesc },
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.erase_undo))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall)) {
                OutlinedButton(
                    onClick = { onIntent(EraseIntent.Cancel) },
                    enabled = controls.cancelEnabled,
                    modifier = Modifier.heightIn(min = TOUCH),
                ) {
                    Text(stringResource(R.string.erase_cancel))
                }
                Button(
                    onClick = { onIntent(EraseIntent.Apply) },
                    enabled = controls.applyEnabled,
                    modifier = Modifier.heightIn(min = TOUCH),
                ) {
                    Text(stringResource(R.string.erase_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrushPicker(selected: EraseBrushSize, enabled: Boolean, onPick: (EraseBrushSize) -> Unit) {
    val group = stringResource(R.string.erase_size_picker)
    val sizes = EraseBrushSize.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = group
                isTraversalGroup = true
            },
    ) {
        sizes.forEachIndexed { i, size ->
            SegmentedButton(
                selected = size == selected,
                onClick = { onPick(size) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = sizes.size),
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TOUCH),
            ) {
                Text(stringResource(EraseBrush.label(size)), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Back or Close with marks (docs/design/screens/erase.md "Leave confirm"). */
@Composable
fun EraseDiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.erase_discard_title)) },
        text = { Text(stringResource(R.string.erase_discard_body)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.erase_discard), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.erase_keep)) }
        },
    )
}

private fun View.reject() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

private const val ANNOUNCE_MS = 1000L
private const val TRAIL_ALPHA = 0.25f
private val TOUCH = SplatDimens.TouchTarget
private val RING = 2.dp
private val RING_OUTLINE = 1.dp
private val PROGRESS = 24.dp
private val SIDE_PANEL = 360.dp
private val WIDE_WIDTH = 600.dp
private val SHORT_HEIGHT = 480.dp
