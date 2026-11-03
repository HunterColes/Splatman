package com.huntercoles.splatman.library.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.domain.model.OpenTarget
import com.huntercoles.splatman.library.domain.model.ScanThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val NAME_MAX_LINES = 2
private const val PLACEHOLDER_COUNT = 4
private const val THUMB_RATIO = 4f / 5f
private const val FAILED_ALPHA = 0.38f
private const val DIMMED_ALPHA = 0.38f
private const val SHIMMER_MS = 1_200
private const val SHIMMER_LOW = 0.4f
private const val WIDE_MIN_DP = 600
private const val PERCENT = 100f
private val EmptyMaxWidth = 360.dp

/** The Library's ways out: everything but opening a card goes through these. */
data class LibraryActions(
    val onOpen: (OpenTarget) -> Unit,
    val onScan: () -> Unit,
    val onOpenFile: () -> Unit,
    val onSettings: () -> Unit,
    val onAbout: () -> Unit,
    /** The "can't track motion" snackbar's Details: the onboarding AR page. */
    val onArDetails: () -> Unit = {},
)

/**
 * Home (docs/design/screens/library.md): the scans newest first in an adaptive grid, an empty state with Start a
 * scan, Open a file and Try a sample, selection mode with Delete and an Undo snackbar, and TalkBack actions per card.
 * When the phone cannot track motion the Scan FAB is dimmed and a tap shows [arUnsupportedTitle] with Details.
 */
@Composable
fun LibraryScreen(
    actions: LibraryActions,
    arUnsupportedTitle: String,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onIntent = viewModel::onIntent
    val grid = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<LibraryCard?>(null) }

    val details = stringResource(R.string.lib_ar_details)

    BackHandler(enabled = state.selecting) { onIntent(LibraryIntent.ExitSelection) }
    UndoSnackbar(state.undo, snackbar, onIntent)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LibraryEvent.OpenScan -> actions.onScan()
                LibraryEvent.ArUnsupported -> launch {
                    val result = snackbar.showSnackbar(arUnsupportedTitle, actionLabel = details)
                    if (result == SnackbarResult.ActionPerformed) actions.onArDetails()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.selecting) {
                SelectionBar(state.selectedIds.size, onIntent)
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.lib_title)) },
                    actions = { HomeActions(actions) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (state.showFab) {
                val scanDescription = stringResource(R.string.lib_scan_description)
                val content = contentColorFor(FloatingActionButtonDefaults.containerColor)
                ExtendedFloatingActionButton(
                    onClick = { onIntent(LibraryIntent.Scan) },
                    contentColor = if (state.fabDimmed) content.copy(alpha = DIMMED_ALPHA) else content,
                    expanded = grid.isScrollingUp(),
                    icon = { Icon(Icons.Rounded.ViewInAr, contentDescription = null) },
                    text = { Text(stringResource(R.string.lib_scan)) },
                    modifier = Modifier.semantics { contentDescription = scanDescription },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val edge = if (maxWidth >= WIDE_MIN_DP.dp) SplatDimens.SpacingLarge else SplatDimens.SpacingDefault
            when (val content = state.content) {
                LibraryContent.Empty -> EmptyState(
                    onScan = { onIntent(LibraryIntent.Scan) },
                    onOpenFile = actions.onOpenFile,
                    onSample = state.firstSample?.let { sample -> { actions.onOpen(OpenTarget.Viewer(sample.id)) } },
                )
                LibraryContent.Loading -> CardGrid(grid, edge) {
                    items(PLACEHOLDER_COUNT) { PlaceholderCard() }
                }
                is LibraryContent.Cards -> ScanCardGrid(
                    cards = content.cards,
                    grid = grid,
                    edge = edge,
                    selecting = state.selecting,
                    onClick = { card ->
                        if (state.selecting) onIntent(LibraryIntent.Toggle(card.id)) else actions.onOpen(card.target)
                    },
                    onLongClick = { card -> onIntent(LibraryIntent.LongPress(card.id)) },
                    onRename = { card -> renaming = card },
                    onDelete = { card -> onIntent(LibraryIntent.RequestDelete(setOf(card.id))) },
                )
            }
        }
    }

    state.confirmDelete?.let { ids -> DeleteDialog(ids.size, onIntent) }
    renaming?.let { card ->
        RenameDialog(
            card = card,
            onRename = { name -> onIntent(LibraryIntent.Rename(card.id, name)) },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun HomeActions(actions: LibraryActions) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = actions.onOpenFile) {
        Icon(Icons.Rounded.FileOpen, contentDescription = stringResource(R.string.lib_import))
    }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.lib_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_settings)) },
                onClick = {
                    menu = false
                    actions.onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_about)) },
                onClick = {
                    menu = false
                    actions.onAbout()
                },
            )
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onIntent: (LibraryIntent) -> Unit) {
    val announcement = stringResource(R.string.lib_selection_mode, count)
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.lib_selected, count),
                modifier = Modifier.semantics {
                    contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = { onIntent(LibraryIntent.ExitSelection) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.lib_close_selection))
            }
        },
        actions = {
            IconButton(onClick = { onIntent(LibraryIntent.RequestDelete()) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

/** The grid of scan cards (also what the device test draws at 360 dp). */
@Composable
fun ScanCardGrid(
    cards: List<LibraryCard>,
    modifier: Modifier = Modifier,
    grid: LazyGridState = rememberLazyGridState(),
    edge: Dp = SplatDimens.SpacingDefault,
    selecting: Boolean = false,
    onClick: (LibraryCard) -> Unit = {},
    onLongClick: (LibraryCard) -> Unit = {},
    onRename: (LibraryCard) -> Unit = {},
    onDelete: (LibraryCard) -> Unit = {},
) {
    Box(modifier) {
        CardGrid(grid, edge) {
            items(cards, key = { it.id }) { card ->
                ScanCard(
                    card = card,
                    selecting = selecting,
                    onClick = { onClick(card) },
                    onLongClick = { onLongClick(card) },
                    onRename = { onRename(card) },
                    onDelete = { onDelete(card) },
                )
            }
        }
    }
}

@Composable
private fun CardGrid(
    grid: LazyGridState,
    edge: Dp,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SplatDimens.LibraryCellMin),
        state = grid,
        modifier = Modifier.widthIn(max = SplatDimens.ContentMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(edge),
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingMedium),
        content = content,
    )
}

@Composable
private fun EmptyState(onScan: () -> Unit, onOpenFile: () -> Unit, onSample: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SplatDimens.SpacingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = EmptyMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
        ) {
            Icon(
                Icons.Rounded.ViewInAr,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SplatDimens.SpacingXXLarge * 2),
            )
            Text(
                stringResource(R.string.lib_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.lib_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onScan, modifier = Modifier.padding(top = SplatDimens.SpacingDefault)) {
                Text(stringResource(R.string.lib_start_scan))
            }
            FilledTonalButton(onClick = onOpenFile) { Text(stringResource(R.string.lib_import)) }
            if (onSample != null) {
                TextButton(onClick = onSample) { Text(stringResource(R.string.lib_empty_sample)) }
            }
        }
    }
}

@Composable
private fun PlaceholderCard() {
    val pulse = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 1f,
        targetValue = SHIMMER_LOW,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmer",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(SplatDimens.CornerMedium),
        modifier = Modifier.fillMaxWidth().alpha(pulse.value).clearAndSetSemantics { },
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(THUMB_RATIO)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Box(Modifier.height(SplatDimens.SpacingXXLarge + SplatDimens.SpacingSmall))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanCard(
    card: LibraryCard,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = Locale.getDefault()
    val shortDate = formatDate(card.createdAtMs, "d MMM", locale)
    val longDate = formatDate(card.createdAtMs, "d MMMM", locale)
    val detail = when (val s = card.state) {
        is CardState.Ready -> stringResource(R.string.lib_card_detail, shortDate, card.sizeText)
        CardState.Captured -> stringResource(R.string.lib_status_captured)
        is CardState.Processing -> s.percent?.let { stringResource(R.string.lib_processing, it) }
            ?: stringResource(R.string.lib_status_processing)
        CardState.Failed -> stringResource(R.string.lib_failed)
    }
    val description = when (val s = card.state) {
        is CardState.Ready -> stringResource(R.string.lib_card_description, card.name, longDate, card.sizeText)
        CardState.Captured -> stringResource(R.string.lib_card_description_captured, card.name, longDate)
        is CardState.Processing -> s.percent?.let {
            stringResource(R.string.lib_card_description_building_percent, card.name, shortDate, it)
        } ?: stringResource(R.string.lib_card_description_building, card.name, longDate)
        CardState.Failed -> stringResource(R.string.lib_card_description_failed, card.name, longDate)
    }
    val renameLabel = stringResource(R.string.lib_rename)
    val deleteLabel = stringResource(R.string.lib_delete)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(SplatDimens.CornerMedium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SplatDimens.TouchTarget)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (selecting) selected = card.selected
                customActions = listOf(
                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            },
    ) {
        Column {
            Thumbnail(card, selecting)
            Column(modifier = Modifier.padding(SplatDimens.SpacingMedium)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(card: LibraryCard, selecting: Boolean) {
    val state = card.state
    val selected = card.selected
    val shape = RoundedCornerShape(SplatDimens.CornerMedium)
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO)) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(if (state == CardState.Failed) FAILED_ALPHA else 1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            card.thumbnail?.let { ThumbnailImage(it) }
        }
        if (state is CardState.Processing) {
            val bar = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            val percent = state.percent
            if (percent == null) {
                LinearProgressIndicator(modifier = bar)
            } else {
                LinearProgressIndicator(progress = { percent / PERCENT }, modifier = bar)
            }
        }
        if (state == CardState.Failed) {
            FailedBadge(Modifier.align(Alignment.TopStart).padding(SplatDimens.SpacingSmall))
        } else if (card.imported) {
            ImportedChip(Modifier.align(Alignment.TopStart).padding(SplatDimens.SpacingSmall))
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(SplatDimens.SpacingSmall),
            )
        }
    }
}

/** The scan's `thumbnail.png`, decoded off the main thread at the card's size; nothing while it decodes or fails. */
@Composable
private fun ThumbnailImage(thumbnail: ScanThumbnail) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        var image by remember(thumbnail, width, height) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(thumbnail, width, height) {
            image = withContext(Dispatchers.IO) {
                ThumbnailDecoder.decode(File(thumbnail.path), width, height)?.asImageBitmap()
            }
        }
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ImportedChip(modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(SplatDimens.CornerSmall),
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.lib_imported),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = SplatDimens.SpacingSmall, vertical = SplatDimens.SpacingXSmall),
        )
    }
}

@Composable
private fun FailedBadge(modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(SplatDimens.CornerSmall),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SplatDimens.SpacingSmall, vertical = SplatDimens.SpacingXSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingXSmall),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.lib_failed), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun UndoSnackbar(undo: PendingDelete?, host: SnackbarHostState, onIntent: (LibraryIntent) -> Unit) {
    val message = undo?.let { stringResource(R.string.lib_deleted, it.ids.size) }
    val action = stringResource(R.string.lib_undo)
    LaunchedEffect(undo) {
        if (message == null) {
            host.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        // The ViewModel closes the window after UNDO_WINDOW_MS; the snackbar stays until it does.
        val result = host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) onIntent(LibraryIntent.Undo) else onIntent(LibraryIntent.CommitDelete)
    }
}

@Composable
private fun DeleteDialog(count: Int, onIntent: (LibraryIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(LibraryIntent.CancelDelete) },
        title = { Text(pluralStringResource(R.plurals.lib_delete_confirm_title, count, count)) },
        text = { Text(stringResource(R.string.lib_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = { onIntent(LibraryIntent.ConfirmDelete) }) {
                Text(stringResource(R.string.lib_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(LibraryIntent.CancelDelete) }) { Text(stringResource(R.string.lib_cancel)) }
        },
    )
}

@Composable
private fun RenameDialog(card: LibraryCard, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.lib_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.lib_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

/** True at the top of the list and while the user scrolls back up; the FAB is extended then. */
@Composable
private fun LazyGridState.isScrollingUp(): Boolean {
    var lastIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var lastOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val up = if (lastIndex != firstVisibleItemIndex) {
                lastIndex > firstVisibleItemIndex
            } else {
                lastOffset >= firstVisibleItemScrollOffset
            }
            lastIndex = firstVisibleItemIndex
            lastOffset = firstVisibleItemScrollOffset
            up
        }
    }.value
}

private fun formatDate(epochMs: Long, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
