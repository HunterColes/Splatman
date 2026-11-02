package com.huntercoles.splatman.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun style(size: TextUnit, line: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size,
    lineHeight = line,
)

/** The full Material 3 scale on the system font, in sp so it follows the user's font size. */
val Typography = Typography(
    displaySmall = style(36.sp, 44.sp, FontWeight.Normal),
    headlineSmall = style(24.sp, 32.sp, FontWeight.Normal),
    titleLarge = style(22.sp, 28.sp, FontWeight.Normal),
    titleMedium = style(16.sp, 24.sp, FontWeight.Medium),
    titleSmall = style(14.sp, 20.sp, FontWeight.Medium),
    bodyLarge = style(16.sp, 24.sp, FontWeight.Normal),
    bodyMedium = style(14.sp, 20.sp, FontWeight.Normal),
    bodySmall = style(12.sp, 16.sp, FontWeight.Normal),
    labelLarge = style(14.sp, 20.sp, FontWeight.Medium),
    labelMedium = style(12.sp, 16.sp, FontWeight.Medium),
    labelSmall = style(11.sp, 16.sp, FontWeight.Medium),
)

/** Coverage %, progress %, timer: tabular figures so changing digits do not jitter. */
val NumeralTextStyle: TextStyle = style(28.sp, 36.sp, FontWeight.Medium).copy(fontFeatureSettings = "tnum")

/** `MaterialTheme.typography.numeral`. */
@Suppress("UnusedReceiverParameter")
val Typography.numeral: TextStyle get() = NumeralTextStyle
