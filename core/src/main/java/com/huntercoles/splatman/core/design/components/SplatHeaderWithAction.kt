package com.huntercoles.splatman.core.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huntercoles.splatman.core.design.SplatColors

/**
 * Standardized header component with title and action button.
 * Used across features for consistent header layout pattern.
 * 
 * @param title The header title text
 * @param onActionClick Callback when the action button is clicked  
 * @param actionContentDescription Accessibility description for action button
 * @param modifier Optional modifier for the header row
 * @param actionContent Optional custom action content. If null, uses default reset button.
 */
@Composable
fun SplatHeaderWithAction(
    title: String,
    onActionClick: () -> Unit,
    actionContentDescription: String = "Reset",
    modifier: Modifier = Modifier,
    actionContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SplatColors.SplatGold,
            modifier = Modifier.weight(1f)
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (actionContent != null) {
                actionContent()
            } else {
                SplatResetButton(
                    onClick = onActionClick,
                    contentDescription = actionContentDescription
                )
            }
        }
    }
}
