package com.huntercoles.splatman.library.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.huntercoles.splatman.core.design.SplatColors
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.domain.model.OpenTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val NAME_MAX_LINES = 2
private const val PLACEHOLDER_COUNT = 4
private const val THUMB_RATIO = 4f / 5f
private const val FAILED_ALPHA = 0.38f
private const val SHIMMER_MS = 1_200
private const val SHIMMER_LOW = 0.4f
private const val WIDE_MIN_DP = 600
private val EmptyMaxWidth = 360.dp

/** The Library's ways out: everything but opening a card goes through these. */
data class LibraryActions(
    val onOpen: (OpenTarget) -> Unit,
    val onScan: () -> Unit,
    val onOpenFile: () -> Unit,
    val onSettings: () -> Unit,
    val onAbout: () -> Unit,
)

/**
 * Home (docs/design/screens/library.md): the scans newest first in an adaptive grid, an empty state with Start a
 * scan, Open a file and Try a sample, selection mode with Delete and an Undo snackbar, and TalkBack actions per card.
 */
@Composable
fun LibraryScreen(
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onIntent = viewModel::onIntent
    val grid = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<LibraryCard?>(null) }

    BackHandler(enabled = state.selecting) { onIntent(LibraryIntent.ExitSelection) }
    UndoSnackbar(state.undo, snackbar, onIntent)

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
                ExtendedFloatingActionButton(
                    onClick = actions.onScan,
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
                    onScan = actions.onScan,
                    onOpenFile = actions.onOpenFile,
                    onSample = state.firstSample?.let { sample -> { actions.onOpen(OpenTarget.Viewer(sample.id)) } },
                )
                LibraryContent.Loading -> CardGrid(grid, edge) {
                    items(PLACEHOLDER_COUNT) { PlaceholderCard() }
                }
                is LibraryContent.Cards -> CardGrid(grid, edge) {
                    items(content.cards, key = { it.id }) { card ->
                        ScanCard(
                            card = card,
                            selecting = state.selecting,
                            onClick = {
                                if (state.selecting) onIntent(LibraryIntent.Toggle(card.id)) else actions.onOpen(card.target)
                            },
                            onLongClick = { onIntent(LibraryIntent.LongPress(card.id)) },
                            onRename = { renaming = card },
                            onDelete = { onIntent(LibraryIntent.RequestDelete(setOf(card.id))) },
                        )
                    }
                }
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
        is CardState.Ready -> stringResource(
            R.string.lib_card_detail,
            shortDate,
            pluralStringResource(R.plurals.lib_splats, s.splatCount, s.splatCount),
        )
        CardState.Captured -> stringResource(R.string.lib_status_captured)
        CardState.Processing -> stringResource(R.string.lib_status_processing)
        CardState.Failed -> stringResource(R.string.lib_failed)
    }
    val description = when (val s = card.state) {
        is CardState.Ready -> stringResource(
            R.string.lib_card_description,
            card.name,
            longDate,
            pluralStringResource(R.plurals.lib_splats, s.splatCount, s.splatCount),
        )
        CardState.Captured -> stringResource(R.string.lib_card_description_captured, card.name, longDate)
        CardState.Processing -> stringResource(R.string.lib_card_description_building, card.name, longDate)
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
            Thumbnail(card.state, selecting, card.selected)
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
private fun Thumbnail(state: CardState, selecting: Boolean, selected: Boolean) {
    val shape = RoundedCornerShape(topStart = SplatDimens.CornerMedium, topEnd = SplatDimens.CornerMedium)
    val fill = when (state) {
        is CardState.Ready -> SplatColors.ViewerBackground
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO)) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(if (state == CardState.Failed) FAILED_ALPHA else 1f)
                .background(fill, shape),
        )
        if (state == CardState.Processing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        if (state == CardState.Failed) {
            FailedBadge(Modifier.align(Alignment.TopStart).padding(SplatDimens.SpacingSmall))
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
