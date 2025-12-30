package com.huntercoles.splatman.core.design.components

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import com.huntercoles.splatman.core.design.SplatColors

/**
 * Provides consistent theming for all number input text fields in the app.
 * This ensures a uniform appearance across configuration screens and calculators.
 * 
 * Visual Style:
 * - Focused: AccentGreen border (#4CAF50)
 * - Unfocused: CardWhite border
 * - Cursor: SplatGold
 * - Selection handles: SplatGold with semi-transparent background
 * - Text: CardWhite
 * - Disabled: Semi-transparent borders and SplatGold text
 */
object SplatTextFieldDefaults {
    
    /**
     * Standard colors for dark-themed number input fields.
     * Use this for all numeric text fields to ensure consistency.
     */
    @Composable
    fun colors(
        isLocked: Boolean = false
    ): TextFieldColors {
        return OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (isLocked) SplatColors.CardWhite.copy(alpha = 0.5f) else SplatColors.AccentPurple,
            unfocusedBorderColor = if (isLocked) SplatColors.CardWhite.copy(alpha = 0.5f) else SplatColors.CardWhite,
            focusedTextColor = if (isLocked) SplatColors.SplatGold else SplatColors.CardWhite,
            unfocusedTextColor = if (isLocked) SplatColors.SplatGold else SplatColors.CardWhite,
            disabledBorderColor = SplatColors.CardWhite.copy(alpha = 0.5f),
            disabledTextColor = SplatColors.SplatGold,
            focusedLabelColor = if (isLocked) SplatColors.SplatGold else SplatColors.AccentPurple,
            unfocusedLabelColor = if (isLocked) SplatColors.SplatGold else SplatColors.CardWhite,
            disabledLabelColor = SplatColors.SplatGold.copy(alpha = 0.7f),
            cursorColor = SplatColors.SplatGold,
            selectionColors = TextSelectionColors(
                handleColor = SplatColors.SplatGold,
                backgroundColor = SplatColors.SplatGold.copy(alpha = 0.4f)
            )
        )
    }
}
