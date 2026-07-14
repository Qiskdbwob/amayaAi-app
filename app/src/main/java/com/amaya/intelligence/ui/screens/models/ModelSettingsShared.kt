package com.amaya.intelligence.ui.screens.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.ui.components.shared.ModelLeadingIcon

data class ModelSettingsColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val separator: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color
)

@Composable
fun rememberModelSettingsColors(): ModelSettingsColors = if (isSystemInDarkTheme()) {
    ModelSettingsColors(Color(0xFF0B0B0F), Color(0xFF1C1C1E), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.10f), Color(0xFF2C2C2E), Color(0xFFC7C7CC), Color(0xFFF2F2F7), Color(0xFFEBEBF5).copy(alpha = 0.60f), Color(0xFFEBEBF5).copy(alpha = 0.48f))
} else {
    ModelSettingsColors(Color(0xFFF2F2F7), Color.White, Color.Black.copy(alpha = 0.08f), Color(0xFF3C3C43).copy(alpha = 0.13f), Color(0xFFE9E9EE), Color(0xFF5F6368), Color(0xFF1C1C1E), Color(0xFF3C3C43).copy(alpha = 0.62f), Color(0xFF3C3C43).copy(alpha = 0.52f))
}

@Composable
fun InlineError(message: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Could Not Complete Action", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun ModelSection(title: String, colors: ModelSettingsColors, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.headerText, modifier = Modifier.padding(start = 16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = colors.groupSurface, border = BorderStroke(0.7.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
fun ModelSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    colors: ModelSettingsColors,
    onClick: (() -> Unit)?,
    modelId: String? = null,
    providerId: String? = null
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.iconBackground), contentAlignment = Alignment.Center) {
                if (modelId != null) {
                    ModelLeadingIcon(
                        modelId = modelId,
                        providerId = providerId,
                        modifier = Modifier.size(17.dp),
                        tint = colors.iconTint
                    )
                } else {
                    Icon(icon, null, tint = colors.iconTint, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    ),
                    color = colors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = colors.secondaryText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ModelDivider(colors: ModelSettingsColors) {
    HorizontalDivider(Modifier.padding(start = 58.dp), color = colors.separator, thickness = 0.7.dp)
}
