package com.amaya.intelligence.ui.screens.agent.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.ui.components.shared.AgentIcon

private data class IosAgentCardColors(
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color
)

@Composable
private fun iosAgentCardColors(): IosAgentCardColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosAgentCardColors(
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f)
        )
    } else {
        IosAgentCardColors(
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f)
        )
    }
}

@Composable
fun AgentCard(
    config: AgentConfig,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = iosAgentCardColors()
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.iconBackground),
                contentAlignment = Alignment.Center
            ) {
                val iconSpec = AgentIcon.resolve(config.modelId, isSystemInDarkTheme(), name = config.name, providerId = config.providerId)

                if (iconSpec != null) {
                    Icon(
                        painterResource(id = iconSpec.resId),
                        contentDescription = null,
                        tint = if (iconSpec.tintable) colors.iconTint else Color.Unspecified,
                        modifier = Modifier.size(17.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = colors.iconTint,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    config.name.ifBlank { "Unnamed Agent" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (config.modelId.isNotBlank()) append(config.modelId)
                        else append("No model set")
                        append(" · ")
                        append(com.amaya.intelligence.data.remote.api.AmayaProviderRegistry.displayName(config.providerId))
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = colors.secondaryText
                )
            }

            if (config.modelId.isNotBlank()) {
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.width(8.dp))
        }
    }
}
