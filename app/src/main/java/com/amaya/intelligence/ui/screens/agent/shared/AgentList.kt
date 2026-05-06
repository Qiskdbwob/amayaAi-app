package com.amaya.intelligence.ui.screens.agent.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard

@Composable
fun AgentList(
    agentConfigs: List<AgentConfig>,
    onAgentClick: (AgentConfig) -> Unit,
    onToggleEnabled: (AgentConfig, Boolean) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val subscriptionProviderIds = AmayaProviderRegistry.providers.filter { it.isSubscription }.map { it.id }.toSet()
    val subscriptionAgents = agentConfigs.filter { it.providerId in subscriptionProviderIds }
    val regularAgents = agentConfigs.filter { it.providerId !in subscriptionProviderIds }
    val enabledAgents = regularAgents.filter { it.enabled }
    val disabledAgents = regularAgents.filter { !it.enabled }

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
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No agents yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            "Tap + to add your first agent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }

        if (subscriptionAgents.isNotEmpty()) {
            item {
                SettingsSectionCard(title = "Subscription") {
                    subscriptionAgents.forEachIndexed { index, config ->
                        SubscriptionConnectionCard(config = config, onClick = { onAgentClick(config) })
                        if (index < subscriptionAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
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
                        AgentCard(
                            config = config,
                            onClick = { onAgentClick(config) },
                            onToggleEnabled = { enabled -> onToggleEnabled(config, enabled) }
                        )
                        if (index < enabledAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
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
                        AgentCard(
                            config = config,
                            onClick = { onAgentClick(config) },
                            onToggleEnabled = { enabled -> onToggleEnabled(config, enabled) }
                        )
                        if (index < disabledAgents.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
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
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    AmayaProviderRegistry.displayName(config.providerId),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                val count = config.enabledModelIds.filter { it.isNotBlank() && it != config.providerId }.distinct().size
                Text(
                    if (count > 0) "$count models enabled" else "No models enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
