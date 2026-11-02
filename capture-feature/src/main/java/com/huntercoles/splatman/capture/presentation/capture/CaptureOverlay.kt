package com.huntercoles.splatman.capture.presentation.capture

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.recording.ArcSide
import com.huntercoles.splatman.capture.recording.Coverage
import com.huntercoles.splatman.capture.recording.CoverageBand
import com.huntercoles.splatman.capture.recording.CoverageCells
import com.huntercoles.splatman.capture.recording.RecorderState
import com.huntercoles.splatman.capture.recording.ringDescription
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.numeral
import com.huntercoles.splatman.core.design.splatDuration
import kotlin.math.roundToInt

private val ScrimPill = RoundedCornerShape(SplatDimens.CornerDefault)
private val Reticle = 64.dp
private val RingLarge = 200.dp
private val WideLayout = 600.dp

/** Everything drawn over the live camera (docs/design/screens/capture.md, Layout). */
@Composable
internal fun CaptureOverlay(
    state: CaptureUiState,
    fontScale: Float,
    onIntent: (CaptureIntent) -> Unit,
    onHelp: () -> Unit,
) {
    var largeRing by rememberSaveable { mutableStateOf(false) }
    val finishing = state.stage == CaptureStage.FINISHING
    val controlsShown = state.stage != CaptureStage.STARTING && !finishing
    val dropExtras = fontScale >= 2f
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideLayout
        if (state.stage == CaptureStage.PAUSED) {
            Box(Modifier.fillMaxSize().background(SplatColors.OverlayScrim))
        }
        if (!finishing) ReticleMark(state.reticle, state.subjectSet)

        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(SplatDimens.SpacingSmall)) {
            TopBar(state.status, onClose = { onIntent(CaptureIntent.Close) }, onHelp = onHelp)
            WarningBanner(state.warning)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(SplatDimens.SpacingDefault),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault),
        ) {
            state.instruction?.let {
                InstructionPill(it, showHint = !dropExtras, recorder = state.recorder)
            }
            if (!wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        RingSlot(state, SplatDimens.CoverageRing, largeRing, finishing) { largeRing = !largeRing }
                    }
                    Fade(controlsShown) {
                        CaptureButtonWithCaption(state, showCaption = !dropExtras, onIntent = onIntent)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Fade(controlsShown) {
                            FinishButton(state.ready) { onIntent(CaptureIntent.Stop) }
                        }
                    }
                }
            }
        }
        if (wide) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .navigationBarsPadding()
                    .padding(SplatDimens.SpacingLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingLarge),
            ) {
                RingSlot(state, SplatDimens.CoverageRingWide, largeRing, finishing) { largeRing = !largeRing }
                Fade(controlsShown) {
                    CaptureButtonWithCaption(state, showCaption = !dropExtras, onIntent = onIntent)
                }
                Fade(controlsShown) {
                    FinishButton(state.ready) { onIntent(CaptureIntent.Stop) }
                }
            }
        }
        if (largeRing && !finishing && state.stage != CaptureStage.STARTING) {
            Box(Modifier.align(Alignment.Center)) {
                CoverageRing(state.cells, state.facingSegment, RingLarge) { largeRing = false }
            }
        }
    }
}

/** Fades [content] in and out with [visible] (outside any Row or Column scope's own overload). */
@Composable
private fun Fade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

/** The small ring, hidden while starting and while the large one shows; shrinks away while finishing. */
@Composable
private fun RingSlot(state: CaptureUiState, size: Dp, large: Boolean, finishing: Boolean, onToggle: () -> Unit) {
    if (state.stage == CaptureStage.STARTING) {
        Spacer(Modifier.size(size))
        return
    }
    val shrink by animateFloatAsState(
        targetValue = if (finishing) 0f else 1f,
        animationSpec = tween(splatDuration(SplatMotion.DurationLong), easing = SplatMotion.EasingStandard),
        label = "ringShrink",
    )
    Box(Modifier.size(size).scale(shrink).alpha(if (large) 0f else 1f)) {
        CoverageRing(state.cells, state.facingSegment, size, onToggle)
    }
}

@Composable
private fun TopBar(status: StatusPill, onClose: () -> Unit, onHelp: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ScrimIconButton(onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cap_close))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StatusPillView(status) }
        ScrimIconButton(onHelp) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = stringResource(R.string.cap_help))
        }
    }
}

@Composable
private fun ScrimIconButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(SplatDimens.TouchTarget).background(SplatColors.OverlayScrim, CircleShape),
    ) {
        CompositionLocalProvider(LocalContentColor provides SplatColors.OverlayOnScrim) { icon() }
    }
}

@Composable
private fun StatusPillView(status: StatusPill) {
    val scheme = MaterialTheme.colorScheme
    val (dot, label) = when (status) {
        StatusPill.STARTING -> scheme.tertiary to R.string.cap_status_starting
        StatusPill.TRACKING -> scheme.secondary to R.string.cap_status_tracking
        StatusPill.LIMITED -> scheme.tertiary to R.string.cap_status_limited
        StatusPill.LOST -> scheme.error to R.string.cap_status_lost
        StatusPill.PAUSED -> scheme.tertiary to R.string.cap_paused
    }
    Row(
        modifier = Modifier
            .background(SplatColors.OverlayScrim, ScrimPill)
            .padding(horizontal = SplatDimens.SpacingMedium, vertical = SplatDimens.SpacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = SplatColors.OverlayOnScrim)
    }
}

@Composable
private fun WarningBanner(warning: GuidanceWarning?) {
    // Keeps the last text while it slides out.
    var last by remember { mutableStateOf(warning ?: GuidanceWarning.LOST) }
    if (warning != null) last = warning
    AnimatedVisibility(
        visible = warning != null,
        enter = slideInVertically(
            tween(splatDuration(SplatMotion.DurationMedium), easing = SplatMotion.EasingEmphasizedDecelerate),
        ) { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val lost = last == GuidanceWarning.LOST
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SplatDimens.SpacingSmall)
                .background(SplatColors.OverlayScrimStrong, ScrimPill)
                .padding(SplatDimens.SpacingDefault)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium),
        ) {
            Icon(
                if (lost) Icons.Rounded.ErrorOutline else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (lost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                stringResource(last.label()),
                style = MaterialTheme.typography.bodyLarge,
                color = SplatColors.OverlayOnScrim,
            )
        }
    }
}

@StringRes
internal fun GuidanceWarning.label(): Int = when (this) {
    GuidanceWarning.LOST -> R.string.cap_warn_lost
    GuidanceWarning.DARK -> R.string.cap_warn_dark
    GuidanceWarning.FAST -> R.string.cap_warn_fast
    GuidanceWarning.CLOSE -> R.string.cap_warn_close
    GuidanceWarning.FAR -> R.string.cap_warn_far
}

/** Four corner brackets at the subject's view position, or at the centre at half alpha before one is set. */
@Composable
private fun ReticleMark(at: ScreenPoint?, subjectSet: Boolean) {
    val pop = remember { Animatable(1f) }
    val motionMs = splatDuration(SplatMotion.DurationShort)
    LaunchedEffect(subjectSet) {
        if (subjectSet) {
            pop.snapTo(0.6f)
            pop.animateTo(1f, tween(motionMs, easing = SplatMotion.EasingEmphasizedDecelerate))
        }
    }
    val sizePx = with(LocalDensity.current) { Reticle.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val p = if (subjectSet && at != null) at else ScreenPoint(w / 2, h / 2)
        Canvas(
            modifier = Modifier
                .offset { IntOffset((p.x - sizePx / 2).roundToInt(), (p.y - sizePx / 2).roundToInt()) }
                .size(Reticle)
                .scale(pop.value)
                .alpha(if (subjectSet) 1f else 0.5f),
        ) {
            val s = size.minDimension
            val arm = s / 4
            val stroke = 2.dp.toPx()
            val c = SplatColors.OverlayOnScrim
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
                drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
            }
            corner(0f, 0f, 1f, 1f)
            corner(s, 0f, -1f, 1f)
            corner(0f, s, 1f, -1f)
            corner(s, s, -1f, -1f)
        }
    }
}

@Composable
private fun InstructionPill(instruction: Instruction, showHint: Boolean, recorder: RecorderState) {
    val text = when (instruction) {
        Instruction.SAVE_FAILED -> stringResource(
            R.string.capture_save_failed,
            (recorder as? RecorderState.Failed)?.reason.orEmpty(),
        )
        else -> stringResource(instruction.label())
    }
    Column(
        modifier = Modifier
            .background(SplatColors.OverlayScrim, ScrimPill)
            .padding(horizontal = SplatDimens.SpacingDefault, vertical = SplatDimens.SpacingSmall)
            .semantics(mergeDescendants = true) { contentDescription = text },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = SplatColors.OverlayOnScrim,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (instruction == Instruction.AIM && showHint) {
            Text(
                stringResource(R.string.cap_aim_hint),
                style = MaterialTheme.typography.bodySmall,
                color = SplatColors.OverlayOnScrim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@StringRes
private fun Instruction.label(): Int = when (this) {
    Instruction.STARTING -> R.string.cap_starting
    Instruction.FIND_SURFACE -> R.string.cap_find_surface
    Instruction.AIM -> R.string.cap_aim
    Instruction.WALK -> R.string.cap_walk
    Instruction.GO_HIGHER -> R.string.cap_go_higher
    Instruction.GO_LOWER -> R.string.cap_go_lower
    Instruction.PAUSED -> R.string.cap_paused
    Instruction.READY -> R.string.cap_ready
    Instruction.NOTHING_RECORDED -> R.string.capture_nothing_recorded
    Instruction.SAVE_FAILED -> R.string.capture_save_failed
}

@Composable
private fun CaptureButtonWithCaption(state: CaptureUiState, showCaption: Boolean, onIntent: (CaptureIntent) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingXSmall),
    ) {
        CaptureButton(state.stage, onIntent)
        if (showCaption) {
            val recording = state.stage == CaptureStage.CAPTURING || state.stage == CaptureStage.PAUSED
            Text(
                if (recording) stringResource(R.string.cap_caption, elapsed(state.elapsedMs), state.framesKept) else " ",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = SplatColors.OverlayOnScrim,
            )
        }
    }
}

/** `1:24`: minutes and seconds. */
internal fun elapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CaptureButton(stage: CaptureStage, onIntent: (CaptureIntent) -> Unit) {
    val (description, intent) = when (stage) {
        CaptureStage.CAPTURING -> R.string.cap_pause_capture to CaptureIntent.Pause
        CaptureStage.PAUSED -> R.string.cap_resume_capture to CaptureIntent.Resume
        else -> R.string.cap_start_capture to CaptureIntent.Record
    }
    val enabled = stage == CaptureStage.AIM || stage == CaptureStage.CAPTURING || stage == CaptureStage.PAUSED
    val label = stringResource(description)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(SplatDimens.CaptureButton)
            .alpha(if (enabled) 1f else 0.38f)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onIntent(intent) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(SplatDimens.CaptureButton - 16.dp)) {
            when (stage) {
                CaptureStage.CAPTURING -> {
                    val side = size.minDimension * 0.55f
                    drawRoundRect(
                        SplatColors.RecordRed,
                        topLeft = Offset((size.width - side) / 2, (size.height - side) / 2),
                        size = Size(side, side),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                }
                CaptureStage.PAUSED -> {
                    val s = size.minDimension
                    val path = Path().apply {
                        moveTo(s * 0.38f, s * 0.28f)
                        lineTo(s * 0.74f, s * 0.5f)
                        lineTo(s * 0.38f, s * 0.72f)
                        close()
                    }
                    drawPath(path, Color.White)
                }
                else -> drawCircle(primary)
            }
        }
    }
}

@Composable
private fun FinishButton(ready: Boolean, onFinish: () -> Unit) {
    val colors = if (ready) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    FilledTonalButton(onClick = onFinish, enabled = ready, colors = colors) {
        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(SplatDimens.SpacingSmall))
        Text(stringResource(R.string.cap_finish))
    }
}

/**
 * The coverage ring: three concentric rings of [Coverage.SEGMENTS] segments (outer = low, middle = level, inner =
 * high), covered ones filled `secondary`, the rest an `outline` stroke; a `primary` tick outside at the [facing]
 * segment; the total in the centre. Segment 0 is at the bottom; growing azimuth runs anticlockwise on screen (the
 * user's right when they stand at the bottom). Tap toggles the large version.
 */
@Composable
internal fun CoverageRing(cells: CoverageCells, facing: Int, size: Dp, onToggle: () -> Unit) {
    val description = ringDescriptionText(cells, facing)
    val toggle = stringResource(R.string.cap_ring_toggle)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .background(SplatColors.OverlayScrim, CircleShape)
            .clickable(role = Role.Button, onClickLabel = toggle, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(SplatDimens.SpacingSmall)) {
            // Half the token stroke per band, so the three bands leave the centre free for the total.
            val band = SplatDimens.CoverageRingStroke.toPx() / 2 * (size / SplatDimens.CoverageRing)
            val gap = 1.5.dp.toPx()
            val tick = 4.dp.toPx()
            val outer = this.size.minDimension / 2 - tick - gap
            val sweep = 360f / Coverage.SEGMENTS
            CoverageBand.entries.forEachIndexed { i, b ->
                val radius = outer - band / 2 - i * (band + gap)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                for (seg in 0 until Coverage.SEGMENTS) {
                    // Canvas angles run clockwise from +x; 90 is the bottom.
                    val start = 90f - (seg + 1) * sweep
                    if (cells.covered(b, seg)) {
                        drawArc(scheme.secondary, start + 1f, sweep - 2f, false, topLeft, arcSize, style = Stroke(band))
                    } else {
                        drawArc(scheme.outline, start + 1f, sweep - 2f, false, topLeft, arcSize, style = Stroke(1.dp.toPx()))
                    }
                }
            }
            val angle = Math.toRadians((90f - (facing + 0.5f) * sweep).toDouble())
            val r0 = outer + gap / 2
            val r1 = outer + gap / 2 + tick
            drawLine(
                scheme.primary,
                Offset(center.x + r0 * kotlin.math.cos(angle).toFloat(), center.y + r0 * kotlin.math.sin(angle).toFloat()),
                Offset(center.x + r1 * kotlin.math.cos(angle).toFloat(), center.y + r1 * kotlin.math.sin(angle).toFloat()),
                2.dp.toPx(),
                StrokeCap.Round,
            )
        }
        Text(
            stringResource(R.string.cap_coverage, cells.percent),
            style = MaterialTheme.typography.numeral.copy(
                fontSize = MaterialTheme.typography.labelSmall.fontSize * (size / SplatDimens.CoverageRing),
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight * (size / SplatDimens.CoverageRing),
            ),
            color = SplatColors.OverlayOnScrim,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.width(size * 0.45f),
        )
    }
}

@Composable
private fun ringDescriptionText(cells: CoverageCells, facing: Int): String {
    val d = ringDescription(cells, facing)
    val gap = d.missing ?: return stringResource(R.string.cap_ring_complete, d.percent)
    val band = stringResource(
        when (gap.band) {
            CoverageBand.LOW -> R.string.cap_band_low
            CoverageBand.LEVEL -> R.string.cap_band_level
            CoverageBand.HIGH -> R.string.cap_band_high
        },
    )
    val side = stringResource(
        when (gap.side) {
            ArcSide.NEAR -> R.string.cap_side_near
            ArcSide.LEFT -> R.string.cap_side_left
            ArcSide.RIGHT -> R.string.cap_side_right
            ArcSide.FAR -> R.string.cap_side_far
            ArcSide.ALL_ROUND -> R.string.cap_side_all
        },
    )
    return stringResource(R.string.cap_ring_missing, d.percent, band, side)
}
