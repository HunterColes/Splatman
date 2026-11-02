package com.huntercoles.splatman.core.design

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Motion tokens (docs/design/tokens.md). */
object SplatMotion {
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EasingEmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Icon swaps, chip select, ring segment fill (ms). */
    const val DurationShort = 150

    /** Banners in and out, sheet open (ms). */
    const val DurationMedium = 300

    /** Screen transitions, capture to processing (ms). */
    const val DurationLong = 500

    /** With animations removed, transitions become a crossfade this long (ms). */
    const val ReducedMotionCrossfade = 100

    /** A live warning stays at least this long, so it does not flicker (ms). */
    const val GuidanceMinShow = 1500L

    /** The same warning does not repeat sooner than this (ms). */
    const val GuidanceCooldown = 4000L

    /** [millis], or 0 when the system animator scale is 0 ("remove animations"). */
    fun duration(millis: Int, animatorScale: Float): Int = if (animatorScale == 0f) 0 else millis
}

/** The system animator duration scale (1 when unset). */
@Composable
fun rememberAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
}

/** [millis], or 0 when the user removed animations. */
@Composable
fun splatDuration(millis: Int): Int = SplatMotion.duration(millis, rememberAnimatorScale())
