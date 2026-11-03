package com.huntercoles.splatman.library.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.core.preferences.ExportFormat
import com.huntercoles.splatman.core.preferences.ThemeMode
import com.huntercoles.splatman.library.R
import com.huntercoles.splatman.library.data.storage.StorageSummary

private enum class SettingsDialog { Theme, ExportFormat, ClearFrames }

/** Settings (docs/design/screens/settings.md): Appearance, Scanning, Export and Storage. */
@Composable
fun SettingsFeature(
    onBack: () -> Unit,
    onTips: () -> Unit,
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.framesFreed.collect { bytes ->
            snackbar.showSnackbar(context.getString(R.string.set_frames_freed, formatBytes(bytes)))
        }
    }
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    SettingsScaffold(title = stringResource(R.string.set_title), onBack = onBack, snackbar = snackbar) {
        GroupHeader(R.string.set_group_appearance)
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_theme)) },
            supportingContent = { Text(stringResource(state.theme.label())) },
            modifier = Modifier.clickable { dialog = SettingsDialog.Theme },
        )
        SwitchItem(R.string.set_haptics, null, state.haptics, viewModel::setHaptics)

        GroupHeader(R.string.set_group_scanning)
        SwitchItem(R.string.set_keep_frames, R.string.set_keep_frames_body, state.keepFrames, viewModel::setKeepFrames)
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_tips)) },
            modifier = Modifier.clickable(onClick = onTips),
        )

        GroupHeader(R.string.set_group_export)
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_export_format)) },
            supportingContent = { Text(stringResource(state.exportFormat.label())) },
            modifier = Modifier.clickable { dialog = SettingsDialog.ExportFormat },
        )

        GroupHeader(R.string.set_group_storage)
        val storage = state.storage
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_storage)) },
            supportingContent = {
                Text(
                    when (val line = storageLine(storage)) {
                        StorageLine.Counting -> stringResource(R.string.set_storage_counting)
                        StorageLine.Empty -> stringResource(R.string.set_storage_empty)
                        is StorageLine.Used -> pluralStringResource(
                            R.plurals.set_storage_body,
                            line.scanCount,
                            formatBytes(line.usedBytes),
                            line.scanCount,
                        )
                    },
                )
            },
        )
        val canClear = storage != null && storage.clearableBytes > 0
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_clear_frames)) },
            supportingContent = {
                Text(
                    when {
                        storage == null -> stringResource(R.string.set_storage_counting)
                        canClear -> stringResource(R.string.set_clear_frames_body, formatBytes(storage.clearableBytes))
                        else -> stringResource(R.string.set_no_frames)
                    },
                )
            },
            modifier = Modifier.clickable(enabled = canClear) { dialog = SettingsDialog.ClearFrames },
            colors = if (canClear) {
                ListItemDefaults.colors()
            } else {
                val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                ListItemDefaults.colors(
                    headlineColor = disabled,
                    supportingColor = disabled,
                )
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.set_about)) },
            modifier = Modifier.clickable(onClick = onAbout),
        )
    }

    when (dialog) {
        SettingsDialog.Theme -> ChoiceDialog(
            title = R.string.set_theme,
            options = listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light),
            selected = state.theme,
            label = { it.label() },
            onChoose = { viewModel.setTheme(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ExportFormat -> ChoiceDialog(
            title = R.string.set_export_format,
            options = ExportFormat.entries,
            selected = state.exportFormat,
            label = { it.label() },
            onChoose = { viewModel.setExportFormat(it); dialog = null },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ClearFrames -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.set_clear_frames_title)) },
            text = {
                Text(stringResource(R.string.set_clear_frames_body, formatBytes(state.storage?.clearableBytes ?: 0)))
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; viewModel.clearFrames() }) {
                    Text(stringResource(R.string.set_clear_frames_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.lib_cancel)) }
            },
        )
        null -> Unit
    }
}

/** Which storage line Settings shows: still counting, no scans yet, or the plural "used by n scans". */
internal sealed interface StorageLine {
    data object Counting : StorageLine
    data object Empty : StorageLine
    data class Used(val usedBytes: Long, val scanCount: Int) : StorageLine
}

internal fun storageLine(summary: StorageSummary?): StorageLine = when {
    summary == null -> StorageLine.Counting
    summary.scanCount <= 0 -> StorageLine.Empty
    else -> StorageLine.Used(summary.usedBytes, summary.scanCount)
}

private const val DISABLED_ALPHA = 0.38f

@StringRes
private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.System -> R.string.set_theme_system
    ThemeMode.Dark -> R.string.set_theme_dark
    ThemeMode.Light -> R.string.set_theme_light
}

@StringRes
private fun ExportFormat.label(): Int = when (this) {
    ExportFormat.Ply -> R.string.set_format_ply
    ExportFormat.Splat -> R.string.set_format_splat
}

/** The Settings and About frame: a large top bar with Back over a scrolling column, 840 dp at most. */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.set_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SplatDimens.ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** A group's `titleSmall` header in `primary`, shared by Settings and About. */
@Composable
internal fun GroupHeader(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = SplatDimens.SpacingDefault,
            end = SplatDimens.SpacingDefault,
            top = SplatDimens.SpacingLarge,
            bottom = SplatDimens.SpacingSmall,
        ),
    )
}

/** The whole row toggles; the switch is drawn only, so it is not a second focus stop. */
@Composable
private fun SwitchItem(@StringRes title: Int, @StringRes body: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = body?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun <T> ChoiceDialog(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(stringResource(label(option))) },
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = option == selected,
                            role = Role.RadioButton,
                            onClick = { onChoose(option) },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) }
        },
    )
}
