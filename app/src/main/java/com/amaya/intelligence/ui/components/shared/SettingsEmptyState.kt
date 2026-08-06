package com.amaya.intelligence.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.amaya.intelligence.ui.screens.amaya.AmayaGroupedSettingsTokens
import com.amaya.intelligence.ui.screens.amaya.iosAmayaColors

@Composable
fun SettingsEmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    titleColor: Color? = null,
    subtitleColor: Color? = null,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    modifier: Modifier = Modifier
) {
    val colors = iosAmayaColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(AmayaGroupedSettingsTokens.emptyStateIconSize)
                    .clip(CircleShape)
                    .background(iconBackground ?: colors.iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(AmayaGroupedSettingsTokens.emptyStateIconGlyphSize),
                    tint = iconTint ?: colors.iconTint
                )
            }
            Spacer(Modifier.height(AmayaGroupedSettingsTokens.emptyStateIconGap))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = titleColor ?: colors.primaryText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(AmayaGroupedSettingsTokens.emptyStateTitleGap))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor ?: colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        if (buttonText != null && onButtonClick != null) {
            Spacer(Modifier.height(AmayaGroupedSettingsTokens.emptyStateActionGap))
            Button(onClick = onButtonClick) {
                Text(buttonText)
            }
        }
    }
}
