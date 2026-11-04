package com.huntercoles.splatman.capture.presentation.capture

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
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
import com.huntercoles.splatman.capture.recording.RoomCells
import com.huntercoles.splatman.capture.recording.RoomDescription
import com.huntercoles.splatman.capture.recording.RoomSide
import com.huntercoles.splatman.capture.recording.ringDescription
import com.huntercoles.splatman.capture.recording.roomDescription
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.design.SplatMotion
import com.huntercoles.splatman.core.design.numeral
import com.huntercoles.splatman.core.design.splatDuration
import com.huntercoles.splatman.splat.capture.ScanMode
import kotlin.math.roundToInt

private val ScrimPill = RoundedCornerShape(SplatDimens.CornerDefault)
private val Reticle = 64.dp
private val RingLarge = 200.dp
private val WideLayout = 600.dp
private val ModeSegment = 120.dp

/**
 * Everything drawn over the live camera (docs/design/screens/capture.md, Layout and Room mode). [largeRingOpen]: the
 * large gauge shows from the start (the goldens; the user opens it with a tap on the gauge).
 */
@Composable
internal fun CaptureOverlay(
    state: CaptureUiState,
    fontScale: Float,
    onIntent: (CaptureIntent) -> Unit,
    onHelp: () -> Unit,
    largeRingOpen: Boolean = false,
) {
    var largeRing by rememberSaveable { mutableStateOf(largeRingOpen) }
    val finishing = state.stage == CaptureStage.FINISHING
    val controlsShown = state.stage != CaptureStage.STARTING && !finishing
    val dropExtras = fontScale >= 2f
    val room = state.mode == ScanMode.ROOM
    // Focus order Close, Help, Mode switch, Gauge, Capture, Finish: on a wide screen the side column sits above the
    // switch, so it is put last explicitly.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        val wide = maxWidth >= WideLayout
        if (state.stage == CaptureStage.PAUSED) {
            Box(Modifier.fillMaxSize().background(SplatColors.OverlayScrim))
        }
        // Pose-free or Room mode: no subject to aim at, so no reticle (the whole frame is the scan).
        if (!finishing && !state.poseFree && !room) ReticleMark(state.reticle, state.subjectSet)

        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(SplatDimens.SpacingSmall)) {
            TopBar(state.status, room, onClose = { onIntent(CaptureIntent.Close) }, onHelp = onHelp)
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
                InstructionPill(it, showHint = !dropExtras, state = state)
            }
            AnimatedVisibility(
                visible = modeSwitchShown(state),
                enter = fadeIn(tween(splatDuration(SplatMotion.DurationShort))),
                exit = fadeOut(tween(splatDuration(SplatMotion.DurationShort))),
            ) {
                ModeSwitch(
                    mode = state.mode,
                    enabled = state.stage == CaptureStage.AIM,
                    showIcons = !dropExtras,
                    onSelect = { onIntent(CaptureIntent.SelectMode(it)) },
                )
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
                            FinishButton(state.ready, finishEnabled(state), showIcon = !dropExtras) {
                                onIntent(CaptureIntent.Stop)
                            }
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
                    .padding(SplatDimens.SpacingLarge)
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 1f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingLarge),
            ) {
                RingSlot(state, SplatDimens.CoverageRingWide, largeRing, finishing) { largeRing = !largeRing }
                Fade(controlsShown) {
                    CaptureButtonWithCaption(state, showCaption = !dropExtras, onIntent = onIntent)
                }
                Fade(controlsShown) {
                    FinishButton(state.ready, finishEnabled(state)) { onIntent(CaptureIntent.Stop) }
                }
            }
        }
        if (largeRing && !finishing && state.stage != CaptureStage.STARTING && !(room && state.noGauge)) {
            Box(Modifier.align(Alignment.Center)) {
                Gauge(state, RingLarge) { largeRing = false }
            }
            // Back closes the large view before it reaches Close.
            BackHandler { largeRing = false }
        }
    }
}

/** The mode switch shows on the pose-free path while it may change the mode: in Aim (and, disabled, Finding). */
internal fun modeSwitchShown(state: CaptureUiState): Boolean =
    state.poseFree &&
        state.modeSwitchVisible &&
        (state.stage == CaptureStage.AIM || state.stage == CaptureStage.FINDING_SURFACES)

/**
 * Object | Room, an M3 segmented row on a scrim pill; each segment [SplatDimens.TouchTarget] tall and [ModeSegment]
 * wide at least; selected `primary` with the M3 check in place of the icon. 38% while not [enabled]. At 200% font the
 * icons go first ([showIcons] false). The sizes go on the row, not the segments: a height on a segment moves its
 * click area off its bounds (M3 1.3.1's interactive-size padding), so taps miss it; the row's equal weights give each
 * segment half its width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ScanMode, enabled: Boolean, showIcons: Boolean, onSelect: (ScanMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.cap_mode_label)
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = scheme.primary,
        activeContentColor = scheme.onPrimary,
        activeBorderColor = scheme.outline,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = SplatColors.OverlayOnScrim,
        inactiveBorderColor = scheme.outline,
        disabledActiveContainerColor = scheme.primary,
        disabledActiveContentColor = scheme.onPrimary,
        disabledActiveBorderColor = scheme.outline,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = SplatColors.OverlayOnScrim,
        disabledInactiveBorderColor = scheme.outline,
    )
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .background(SplatColors.OverlayScrim, ScrimPill)
            .padding(SplatDimens.SpacingSmall),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .widthIn(min = ModeSegment * ScanMode.entries.size)
                .heightIn(min = SplatDimens.TouchTarget)
                .semantics { contentDescription = label },
        ) {
            val modes = ScanMode.entries
            modes.forEachIndexed { i, m ->
                val selected = m == mode
                SegmentedButton(
                    selected = selected,
                    onClick = { onSelect(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    enabled = enabled,
                    colors = colors,
                    icon = {
                        if (showIcons) {
                            SegmentedButtonDefaults.Icon(active = selected) {
                                Icon(
                                    if (m == ScanMode.ROOM) Icons.Rounded.MeetingRoom else Icons.Rounded.CropFree,
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            }
                        }
                    },
                    label = { Text(stringResource(m.label())) },
                )
            }
        }
    }
}

@StringRes
private fun ScanMode.label(): Int = when (this) {
    ScanMode.OBJECT -> R.string.cap_mode_object
    ScanMode.ROOM -> R.string.cap_mode_room
}

/** The Object coverage ring or, in Room mode, the room gauge. */
@Composable
private fun Gauge(state: CaptureUiState, size: Dp, onToggle: () -> Unit) {
    val roomCells = state.roomCells
    if (state.mode == ScanMode.ROOM && roomCells != null) {
        RoomGauge(roomCells, state.roomPercent, state.facingSegment, estimate = state.poseFree, size, onToggle)
    } else {
        CoverageRing(state.cells, state.facingSegment, size, onToggle)
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
    // Room mode without a gyroscope: no gauge (cap_room_no_gauge says why); the slot keeps the layout.
    if (state.stage == CaptureStage.STARTING || state.mode == ScanMode.ROOM && state.noGauge) {
        Spacer(Modifier.size(size))
        return
    }
    val shrink by animateFloatAsState(
        targetValue = if (finishing) 0f else 1f,
        animationSpec = tween(splatDuration(SplatMotion.DurationLong), easing = SplatMotion.EasingStandard),
        label = "ringShrink",
    )
    Box(Modifier.size(size).scale(shrink).alpha(if (large) 0f else 1f)) {
        Gauge(state, size, onToggle)
    }
}

@Composable
private fun TopBar(status: StatusPill, room: Boolean, onClose: () -> Unit, onHelp: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ScrimIconButton(onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cap_close))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StatusPillView(status, room) }
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
private fun StatusPillView(status: StatusPill, room: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (dot, label) = when (status) {
        // Room mode on the pose-free path: the camera is open, nothing tracks (screens/capture.md, foss column).
        StatusPill.READY -> scheme.secondary to if (room) R.string.cap_status_recording_only else R.string.cap_status_ready
        StatusPill.STARTING -> scheme.tertiary to R.string.cap_status_starting
        StatusPill.TRACKING -> scheme.secondary to R.string.cap_status_tracking
        StatusPill.LIMITED -> scheme.tertiary to R.string.cap_status_limited
        StatusPill.LOST -> scheme.error to R.string.cap_status_lost
        StatusPill.PAUSED -> scheme.tertiary to R.string.cap_paused
        StatusPill.RECORDING -> SplatColors.RecordRed to R.string.cap_status_recording
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

/** The instruction line's text for [instruction] (Room's ROOM_WALK reads `cap_room_no_gauge` without a gyroscope). */
@StringRes
internal fun instructionText(instruction: Instruction, poseFree: Boolean, noGauge: Boolean): Int = when {
    instruction == Instruction.AIM && poseFree -> R.string.cap_pf_aim
    instruction == Instruction.ROOM_WALK && noGauge -> R.string.cap_room_no_gauge
    else -> instruction.label()
}

/** The smaller line under [instruction], or null. */
@StringRes
internal fun instructionHint(instruction: Instruction, poseFree: Boolean): Int? = when (instruction) {
    Instruction.AIM -> if (poseFree) R.string.cap_pf_aim_hint else R.string.cap_aim_hint
    Instruction.ROOM_AIM -> R.string.cap_room_aim_hint
    else -> null
}

@Composable
private fun InstructionPill(instruction: Instruction, showHint: Boolean, state: CaptureUiState) {
    val text = if (instruction == Instruction.SAVE_FAILED) {
        stringResource(R.string.capture_save_failed, (state.recorder as? RecorderState.Failed)?.reason.orEmpty())
    } else {
        stringResource(instructionText(instruction, state.poseFree, state.noGauge))
    }
    val hint = instructionHint(instruction, state.poseFree)?.takeIf { showHint }
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
        if (hint != null) {
            Text(
                stringResource(hint),
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
    Instruction.ROOM_AIM -> R.string.cap_room_aim
    Instruction.ROOM_WALK -> R.string.cap_room_walk
    Instruction.ROOM_WALK_ON -> R.string.cap_room_walk_on
    Instruction.ROOM_LOOK_UP -> R.string.cap_room_look_up
    Instruction.ROOM_LOOK_DOWN -> R.string.cap_room_look_down
    Instruction.ROOM_FULL -> R.string.cap_room_full
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

/**
 * Whether Finish takes a tap. ARCore: only once [CaptureUiState.ready]. Pose-free: as soon as a recording is under way,
 * since gyro coverage is a guide there, not a gate (an emulator or a tripod never fills the ring); Stop with no
 * keyframes lands on the `capture_nothing_recorded` instruction, not a crash.
 */
private fun finishEnabled(state: CaptureUiState): Boolean =
    state.ready ||
        (state.poseFree && (state.stage == CaptureStage.CAPTURING || state.stage == CaptureStage.PAUSED))

/**
 * Tonal while [enabled] but not [ready]; `primary` filled once [ready]; 38% when disabled. The label stays on one
 * line (#380); the narrow layout at 200% font drops the icon ([showIcon]) to make room.
 */
@Composable
private fun FinishButton(ready: Boolean, enabled: Boolean, showIcon: Boolean = true, onFinish: () -> Unit) {
    val colors = if (ready) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    FilledTonalButton(onClick = onFinish, enabled = enabled, colors = colors) {
        if (showIcon) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(SplatDimens.SpacingSmall))
        }
        Text(stringResource(R.string.cap_finish), maxLines = 1, softWrap = false)
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
    Ring(
        fill = { b, seg -> if (cells.covered(b, seg)) RingFill.FULL else RingFill.NONE },
        facing = facing,
        size = size,
        percent = cells.percent,
        description = ringDescriptionText(cells, facing),
        caption = null,
        onToggle = onToggle,
    )
}

/** How much of a ring cell's stroke is filled. */
internal enum class RingFill { NONE, HALF, FULL }

/** A room gauge cell: Covered fills the whole stroke, Seen only its inner half, else the outline. */
internal fun roomFill(cells: RoomCells, band: CoverageBand, segment: Int): RingFill = when {
    cells.covered.covered(band, segment) -> RingFill.FULL
    cells.seen.covered(band, segment) -> RingFill.HALF
    else -> RingFill.NONE
}

/**
 * The room gauge's description parts. With poses (Covered exists) a Seen-only cell counts as missing; on the
 * pose-free path ([estimate]) nothing is ever Covered, so the percent and the missing arc come from the Seen cells,
 * as the gauge shows them.
 */
internal fun roomGaugeDescription(cells: RoomCells, percent: Int, facing: Int, estimate: Boolean): RoomDescription {
    val counted = if (estimate) RoomCells(seen = cells.seen, covered = cells.seen) else cells
    return roomDescription(counted, facing).copy(percent = percent)
}

/**
 * The room gauge (screens/capture.md, The room gauge): the coverage ring's chrome with outward-looking cells (outer
 * floor, middle walls, inner ceiling), Seen as a half fill, Covered as a full one, the [percent] in the centre and,
 * on the pose-free path ([estimate]), `cap_room_estimate` under it. Tap toggles the large version (no walked path:
 * the pose-free path has no positions).
 */
@Composable
internal fun RoomGauge(cells: RoomCells, percent: Int, facing: Int, estimate: Boolean, size: Dp, onToggle: () -> Unit) {
    Ring(
        fill = { b, seg -> roomFill(cells, b, seg) },
        facing = facing,
        size = size,
        percent = percent,
        description = roomDescriptionText(roomGaugeDescription(cells, percent, facing, estimate)),
        caption = if (estimate) stringResource(R.string.cap_room_estimate) else null,
        onToggle = onToggle,
    )
}

@Composable
private fun Ring(
    fill: (CoverageBand, Int) -> RingFill,
    facing: Int,
    size: Dp,
    percent: Int,
    description: String,
    caption: String?,
    onToggle: () -> Unit,
) {
    val toggle = stringResource(R.string.cap_ring_toggle)
    // The ring is a fixed-size picture: its text scales with the ring, not the font setting (else 200% clips it; the
    // description carries the words at any size).
    val scale = (size / SplatDimens.CoverageRing) / LocalDensity.current.fontScale
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
                    when (fill(b, seg)) {
                        RingFill.FULL ->
                            drawArc(scheme.secondary, start + 1f, sweep - 2f, false, topLeft, arcSize, style = Stroke(band))
                        RingFill.HALF -> {
                            // The inner half of the stroke: centred a quarter band in from the cell's radius.
                            val r = radius - band / 4
                            drawArc(
                                scheme.secondary,
                                start + 1f,
                                sweep - 2f,
                                false,
                                Offset(center.x - r, center.y - r),
                                Size(r * 2, r * 2),
                                style = Stroke(band / 2),
                            )
                        }
                        RingFill.NONE ->
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.cap_coverage, percent),
                style = MaterialTheme.typography.numeral.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * scale,
                    lineHeight = MaterialTheme.typography.labelSmall.lineHeight * scale,
                ),
                color = SplatColors.OverlayOnScrim,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.width(size * 0.45f),
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * scale,
                        lineHeight = MaterialTheme.typography.labelSmall.lineHeight * scale,
                    ),
                    color = SplatColors.OverlayOnScrim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun roomDescriptionText(d: RoomDescription): String {
    val gap = d.missing ?: return stringResource(R.string.cap_ring_complete, d.percent)
    val band = stringResource(
        when (gap.band) {
            CoverageBand.LOW -> R.string.cap_room_band_floor
            CoverageBand.LEVEL -> R.string.cap_room_band_walls
            CoverageBand.HIGH -> R.string.cap_room_band_ceiling
        },
    )
    val side = stringResource(
        when (gap.side) {
            RoomSide.AHEAD -> R.string.cap_room_side_ahead
            RoomSide.LEFT -> R.string.cap_room_side_left
            RoomSide.RIGHT -> R.string.cap_room_side_right
            RoomSide.BEHIND -> R.string.cap_room_side_behind
            RoomSide.ALL_ROUND -> R.string.cap_room_side_all
        },
    )
    return stringResource(R.string.cap_ring_missing, d.percent, band, side)
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
