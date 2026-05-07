package com.amaya.intelligence.ui.screens.agent.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard

private data class IosAgentListColors(
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val separator: Color
)

@Composable
private fun iosAgentListColors(): IosAgentListColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosAgentListColors(
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            separator = Color.White.copy(alpha = 0.10f)
        )
    } else {
        IosAgentListColors(
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f)
        )
    }
}

@Composable
fun AgentList(
    agentConfigs: List<AgentConfig>,
    onAgentClick: (AgentConfig) -> Unit,
    onToggleEnabled: (AgentConfig, Boolean) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val colors = iosAgentListColors()
    val subscriptionProviders = remember { AmayaProviderRegistry.providers.filter { it.isSubscription } }
    val subscriptionProviderIds = remember(subscriptionProviders) { subscriptionProviders.map { it.id }.toSet() }
    val subscriptionAgents = remember(agentConfigs, subscriptionProviders) {
        subscriptionProviders.mapNotNull { provider ->
            agentConfigs.filter { it.providerId == provider.id }
                .maxByOrNull { subscriptionEnabledModelCount(it) }
        }
    }
    val regularAgents = remember(agentConfigs, subscriptionProviderIds) {
        agentConfigs.filter { it.providerId !in subscriptionProviderIds }
    }
    val enabledAgents = remember(regularAgents) { regularAgents.filter { it.enabled } }
    val disabledAgents = remember(regularAgents) { regularAgents.filter { !it.enabled } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding,
            bottom = 100.dp
        )
    ) {
        if (agentConfigs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(colors.iconBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = colors.iconTint.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No agents yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText
                        )
                        Text(
                            "Tap + to add your first agent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        if (subscriptionAgents.isNotEmpty()) {
            item {
                SettingsSectionCard(title = "Subscription") {
                    subscriptionAgents.forEachIndexed { index, config ->
                        key(config.id) {
                            SubscriptionConnectionCard(
                                config = config,
                                onClick = { onAgentClick(config) },
                                colors = colors
                            )
                        }
                        if (index < subscriptionAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                color = colors.separator,
                                thickness = 0.7.dp
                            )
                        }
                    }
                }
            }
        }

        if (enabledAgents.isNotEmpty()) {
            item {
                SettingsSectionCard(title = "Enabled") {
                    enabledAgents.forEachIndexed { index, config ->
                        key(config.id) {
                            AgentCard(
                                config = config,
                                onClick = { onAgentClick(config) },
                                onToggleEnabled = { enabled -> onToggleEnabled(config, enabled) }
                            )
                        }
                        if (index < enabledAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                color = colors.separator,
                                thickness = 0.7.dp
                            )
                        }
                    }
                }
            }
        }

        if (disabledAgents.isNotEmpty()) {
            item {
                SettingsSectionCard(title = "Disabled") {
                    disabledAgents.forEachIndexed { index, config ->
                        key(config.id) {
                            AgentCard(
                                config = config,
                                onClick = { onAgentClick(config) },
                                onToggleEnabled = { enabled -> onToggleEnabled(config, enabled) }
                            )
                        }
                        if (index < disabledAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                color = colors.separator,
                                thickness = 0.7.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionConnectionCard(
    config: AgentConfig,
    onClick: () -> Unit,
    colors: IosAgentListColors
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
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
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = colors.iconTint
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    AmayaProviderRegistry.displayName(config.providerId),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText
                )
                val count = subscriptionEnabledModelCount(config)
                Text(
                    if (count > 0) "$count models enabled" else "No models enabled",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = colors.secondaryText
                )
            }
        }
    }
}

private fun subscriptionEnabledModelCount(config: AgentConfig): Int = config.enabledModelIds
    .filter { it.isNotBlank() && it != config.providerId }
    .distinct()
    .size
