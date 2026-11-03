package com.huntercoles.splatman.shell

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.huntercoles.splatman.R
import com.huntercoles.splatman.capture.R as CaptureR
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.LibraryFeature
import com.huntercoles.splatman.viewer.ViewerFeature
import com.huntercoles.splatman.viewer.export.AndroidExportTarget
import com.huntercoles.splatman.viewer.export.ShareRequest
import timber.log.Timber

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

/** Home: the Library screen (#15) with its top bar, FAB and every way out. */
@Composable
internal fun LibraryHome(
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
    onArDetails: () -> Unit,
) {
    val context = LocalContext.current
    LibraryFeature(
        onOpenViewer = onOpenViewer,
        onOpenProcessing = onOpenProcessing,
        onScan = onScan,
        onSettings = onSettings,
        onAbout = onAbout,
        onArDetails = onArDetails,
        arUnsupportedTitle = stringResource(CaptureR.string.ar_unsupported_title),
        modifier = Modifier.fillMaxSize(),
        // The viewer's share intent: ACTION_SEND or ACTION_SEND_MULTIPLE with read granted on the content:// URIs.
        onShare = { uris, names ->
            try {
                context.startActivity(AndroidExportTarget.shareIntent(ShareRequest(uris, names)))
                true
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "No app to share %s", names)
                false
            }
        },
    )
}

/** Full screen, always dark: the viewer of the route's scan id. */
@Composable
internal fun ViewerRoute(onBack: () -> Unit) {
    ViewerFeature(onBack = onBack, modifier = Modifier.fillMaxSize())
}
