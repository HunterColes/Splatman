package com.huntercoles.splatman.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.huntercoles.splatman.R
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.LibraryFeature
import com.huntercoles.splatman.viewer.ViewerFeature

/*
 * Stand-ins until each screen's own ticket lands (docs/design/README.md, Tickets). They only make every route
 * reachable; do not polish them.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.shell_back),
                            )
                        }
                    }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(SplatDimens.SpacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Home. The library's scans and samples, with the two ways out (scan, settings) until #15 redraws it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHome(
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_library_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.shell_settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Rounded.ViewInAr, contentDescription = null) },
                text = { Text(stringResource(R.string.shell_scan)) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LibraryFeature(
                onOpenViewer = onOpenViewer,
                onOpenProcessing = onOpenProcessing,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun ProcessingPlaceholder(scanId: String, onBack: () -> Unit) {
    ShellScaffold(title = stringResource(R.string.shell_processing_title), onBack = onBack) {
        PlaceholderText(stringResource(R.string.shell_scan_id, scanId))
    }
}

/** Full screen, always dark: the existing viewer until #18 takes the scan id. */
@Composable
internal fun ViewerRoute() {
    ViewerFeature(modifier = Modifier.fillMaxSize())
}
