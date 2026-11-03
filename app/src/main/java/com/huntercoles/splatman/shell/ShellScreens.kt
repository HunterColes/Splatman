package com.huntercoles.splatman.shell

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.huntercoles.splatman.capture.R as CaptureR
import com.huntercoles.splatman.library.LibraryFeature
import com.huntercoles.splatman.viewer.ViewerFeature
import com.huntercoles.splatman.viewer.export.AndroidExportTarget
import com.huntercoles.splatman.viewer.export.ShareRequest
import timber.log.Timber

/** Home: the Library screen (#15) with its top bar, FAB and every way out. */
@Composable
internal fun LibraryHome(
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpenImported: (String) -> Unit,
    onOpenProcessing: (String) -> Unit,
    onArDetails: () -> Unit,
) {
    val context = LocalContext.current
    LibraryFeature(
        onOpenViewer = onOpenViewer,
        onOpenImported = onOpenImported,
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
