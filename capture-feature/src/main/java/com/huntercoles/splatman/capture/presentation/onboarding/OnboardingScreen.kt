package com.huntercoles.splatman.capture.presentation.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded._360
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.capture.tracking.FlavourTracking
import com.huntercoles.splatman.capture.tracking.rememberCameraPermissionRequest
import com.huntercoles.splatman.core.design.SplatDimens

private val ColumnMaxWidth = 480.dp
private val IllustrationSize = 200.dp
private val IllustrationIconSize = 96.dp
private val TipIconSize = 40.dp
private val TipGlyphSize = 24.dp
private val DotSize = 8.dp

/**
 * First run, and Settings > Show scanning tips again (docs/design/screens/onboarding.md): three pages, Skip, page
 * dots, the camera permission request and the AR unsupported / needs-install pages. [onDone] gets where to go once
 * it is finished or skipped; the caller marks the first-run flag.
 */
@Composable
fun OnboardingScreen(onDone: (OnboardingExit) -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val availability = remember(context) { FlavourTracking.support(context) }
    val currentState by rememberUpdatedState(state)
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(state.exit) { state.exit?.let { currentOnDone(it) } }

    // Back from the store: ask again whether the install finished (ARCore's rule: from onResume).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentState.installPending && activity != null) {
                val answer = availability.requestInstall(activity, userRequested = false)
                viewModel.onIntent(OnboardingIntent.InstallResult(answer))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestPermission = rememberCameraPermissionRequest { granted ->
        viewModel.onIntent(OnboardingIntent.PermissionResult(granted))
    }

    OnboardingContent(
        state = state,
        onIntent = viewModel::onIntent,
        onAllowCamera = requestPermission,
        onInstall = {
            val answer = activity?.let { availability.requestInstall(it, userRequested = true) }
            if (answer != null) viewModel.onIntent(OnboardingIntent.InstallResult(answer))
        },
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
    onAllowCamera: () -> Unit,
    onInstall: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.page.ordinal) { state.pageCount }
    LaunchedEffect(state.page) {
        if (pagerState.currentPage != state.page.ordinal) pagerState.animateScrollToPage(state.page.ordinal)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onIntent(OnboardingIntent.PageShown(it)) }
    }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = ColumnMaxWidth).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { onIntent(OnboardingIntent.Skip) },
                    modifier = Modifier.height(SplatDimens.TouchTarget),
                ) { Text(stringResource(R.string.onb_skip)) }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                PageBody(page = OnboardingPage.at(index), ar = state.ar, reason = state.arReason)
            }
            BottomBar(
                state = state,
                displayedPage = OnboardingPage.at(pagerState.currentPage),
                onIntent = onIntent,
                onAllowCamera = onAllowCamera,
                onInstall = onInstall,
            )
        }
    }
}

@Composable
private fun PageBody(page: OnboardingPage, ar: ArState, reason: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SplatDimens.SpacingLarge),
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (page) {
            OnboardingPage.WELCOME -> {
                Illustration(Icons.Rounded.ViewInAr)
                Title(R.string.onb_welcome_title)
                Body(R.string.onb_welcome_body)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(SplatDimens.SpacingSmall))
                    Text(
                        stringResource(R.string.onb_welcome_private),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OnboardingPage.TIPS -> {
                Title(R.string.onb_tips_title)
                Tip(Icons.Rounded.SlowMotionVideo, R.string.onb_tip_slow)
                Tip(Icons.Rounded._360, R.string.onb_tip_around)
                Tip(Icons.Rounded.WbSunny, R.string.onb_tip_light)
            }
            OnboardingPage.CAMERA -> when (ar) {
                ArState.CHECKING, ArState.SUPPORTED -> {
                    Illustration(Icons.Rounded.CameraAlt)
                    Title(R.string.onb_camera_title)
                    Body(R.string.onb_camera_body)
                }
                ArState.INSTALL_NEEDED -> {
                    Illustration(Icons.Rounded.Download)
                    Title(R.string.ar_install_title)
                    Body(R.string.ar_install_body)
                    Details(reason)
                }
                ArState.UNSUPPORTED -> {
                    Illustration(Icons.Rounded.FolderOpen)
                    Title(R.string.ar_unsupported_title)
                    Body(R.string.ar_unsupported_body)
                    Details(reason)
                }
                ArState.COULDNT_CHECK -> {
                    Illustration(Icons.Rounded.ErrorOutline)
                    Title(R.string.ar_check_failed_title)
                    Body(R.string.ar_check_failed_body)
                    Details(reason)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: OnboardingUiState,
    displayedPage: OnboardingPage,
    onIntent: (OnboardingIntent) -> Unit,
    onAllowCamera: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(SplatDimens.SpacingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault),
    ) {
        PageDots(current = displayedPage.ordinal, count = state.pageCount)
        val wide = Modifier.fillMaxWidth().height(SplatDimens.TouchTarget)
        if (displayedPage != OnboardingPage.CAMERA) {
            Button(onClick = { onIntent(OnboardingIntent.Next) }, modifier = wide) {
                Text(stringResource(R.string.onb_next))
            }
        } else {
            when (state.ar) {
                ArState.CHECKING, ArState.SUPPORTED -> Button(onClick = onAllowCamera, modifier = wide) {
                    Text(stringResource(R.string.onb_camera_allow))
                }
                ArState.INSTALL_NEEDED -> {
                    Button(onClick = onInstall, modifier = wide) { Text(stringResource(R.string.ar_install_action)) }
                    TextButton(onClick = { onIntent(OnboardingIntent.Later) }, modifier = wide) {
                        Text(stringResource(R.string.onb_later))
                    }
                }
                ArState.COULDNT_CHECK -> {
                    Button(onClick = { onIntent(OnboardingIntent.RetryCheck) }, modifier = wide) {
                        Text(stringResource(R.string.capture_retry))
                    }
                    TextButton(onClick = { onIntent(OnboardingIntent.Continue) }, modifier = wide) {
                        Text(stringResource(R.string.onb_continue))
                    }
                }
                ArState.UNSUPPORTED -> {
                    Button(onClick = { onIntent(OnboardingIntent.OpenFile) }, modifier = wide) {
                        Text(stringResource(R.string.onb_open_file))
                    }
                    TextButton(onClick = { onIntent(OnboardingIntent.Continue) }, modifier = wide) {
                        Text(stringResource(R.string.onb_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    val label = stringResource(R.string.onb_page, current + 1, count)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingSmall),
    ) {
        repeat(count) { index ->
            val color = if (index == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(modifier = Modifier.size(DotSize).background(color, CircleShape))
        }
    }
}

/** Decorative: no content description. */
@Composable
private fun Illustration(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(IllustrationSize)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(IllustrationIconSize),
        )
    }
}

@Composable
private fun Title(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Body(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The Details line: the raw reason behind an AR state (nothing while there is none). */
@Composable
private fun Details(reason: String) {
    if (reason.isBlank()) return
    Text(
        stringResource(R.string.ar_details, reason),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Tip(icon: ImageVector, @StringRes text: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SplatDimens.SpacingDefault),
    ) {
        Box(
            modifier = Modifier
                .size(TipIconSize)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(TipGlyphSize),
            )
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
