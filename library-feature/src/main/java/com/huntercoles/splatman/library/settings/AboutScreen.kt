package com.huntercoles.splatman.library.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.huntercoles.splatman.core.design.SplatDimens
import com.huntercoles.splatman.library.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/** The public repository the About screen links to (an intent: the app has no internet permission). */
const val SOURCE_URL = "https://github.com/HunterColes/Splatman"

/** The build writes this asset: one open-source dependency a line (see `parseLicences`). */
const val LICENCES_ASSET = "licences.tsv"

/** The samples' licence note (#5). */
const val SAMPLE_CREDITS_ASSET = "samples/ASSETS-LICENSES.md"

private val APP_ICON = 72.dp

/** About: icon, name, version, tagline, the privacy promise, source, licences and sample credits. */
@Composable
fun AboutFeature(versionName: String, onBack: () -> Unit) {
    var showLicences by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.about_no_browser)
    val onLink: (String) -> Unit = { url ->
        if (!openLink(context, url)) {
            scope.launch { snackbar.showSnackbar(noBrowser) }
        }
    }
    if (showLicences) {
        BackHandler { showLicences = false }
        LicencesScreen(snackbar = snackbar, onLink = onLink, onBack = { showLicences = false })
        return
    }
    val credits by produceState(emptyList<SampleCredit>()) {
        value = readAsset(context, SAMPLE_CREDITS_ASSET)?.let(::parseSampleCredits).orEmpty()
    }
    SettingsScaffold(title = stringResource(R.string.about_title), onBack = onBack, snackbar = snackbar) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SplatDimens.SpacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = remember(context) {
                context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap().asImageBitmap()
            }
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(APP_ICON))
            Text(
                stringResource(R.string.about_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = SplatDimens.SpacingDefault),
            )
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = SplatDimens.SpacingSmall),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_privacy)) },
            leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        )
        LinkItem(
            headline = stringResource(R.string.about_source),
            supporting = SOURCE_URL,
            url = SOURCE_URL,
            onLink = onLink,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_licences)) },
            modifier = Modifier.clickable { showLicences = true },
        )
        if (credits.isNotEmpty()) {
            HorizontalDivider()
            GroupHeader(R.string.about_samples)
            credits.forEach { credit ->
                LinkItem(
                    headline = credit.file,
                    supporting = stringResource(R.string.about_sample_credit, credit.author, credit.licence),
                    url = credit.source,
                    onLink = onLink,
                )
            }
        }
    }
}

/**
 * A list item that opens [url] in the browser: a trailing open-in-new icon and the click label "Open in browser".
 * With no [url] it is a plain, non-interactive item.
 */
@Composable
private fun LinkItem(headline: String, supporting: String, url: String?, onLink: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        trailingContent = url?.let {
            {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = if (url != null) {
            Modifier.clickable(onClickLabel = stringResource(R.string.about_open_link)) { onLink(url) }
        } else {
            Modifier
        },
    )
}

/** Each dependency of the build with its licence, from the generated [LICENCES_ASSET]. */
@Composable
private fun LicencesScreen(snackbar: SnackbarHostState, onLink: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // null while reading; empty when the asset is missing or has no usable line.
    val licences by produceState<List<Licence>?>(null) {
        value = readAsset(context, LICENCES_ASSET)?.let(::parseLicences).orEmpty()
    }
    SettingsScaffold(title = stringResource(R.string.about_licences), onBack = onBack, snackbar = snackbar) {
        val list = licences
        when {
            list == null -> CentredState { CircularProgressIndicator() }
            list.isEmpty() -> CentredState {
                Text(
                    stringResource(R.string.about_licences_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> list.forEach { licence ->
                LinkItem(
                    headline = licence.module,
                    supporting = "${licence.version} · ${licence.licence}",
                    url = licence.url,
                    onLink = onLink,
                )
            }
        }
    }
}

/** A loading or missing state in the scrolling column: centred across, a little way under the top bar. */
@Composable
private fun CentredState(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SplatDimens.SpacingLarge, vertical = SplatDimens.SpacingXXLarge),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private suspend fun readAsset(context: Context, name: String): String? = withContext(Dispatchers.IO) {
    try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        Timber.w(e, "Missing asset %s", name)
        null
    }
}

/** Hands [url] to the browser; the app itself never goes online. False when no app on the phone can open it. */
private fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Timber.w(e, "No app opens %s", url)
    false
}
