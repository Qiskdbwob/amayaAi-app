package com.amaya.intelligence.ui.screens.mcp.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.data.remote.api.McpServerConfig
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard

private data class IosMcpListColors(
    val iconBackground: Color,
    val iconTint: Color,
    val secondaryText: Color,
    val separator: Color
)

@Composable
private fun iosMcpListColors(): IosMcpListColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosMcpListColors(
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            separator = Color.White.copy(alpha = 0.10f)
        )
    } else {
        IosMcpListColors(
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f)
        )
    }
}

@Composable
fun McpServerList(
    servers: List<McpServerConfig>,
    onServerClick: (McpServerConfig) -> Unit,
    onToggleEnabled: (McpServerConfig, Boolean) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    val colors = iosMcpListColors()
    val activeServers = servers.filter { it.enabled }
    val disabledServers = servers.filter { !it.enabled }

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
        if (servers.isEmpty()) {
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
                                Icons.Default.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = colors.iconTint.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No MCP servers",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.secondaryText
                        )
                        Text(
                            "Tap + to add an MCP server",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondaryText.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(32.dp))
                        McpFormatGuide()
                    }
                }
            }
        } else {
            if (activeServers.isNotEmpty()) {
                item {
                    SettingsSectionCard(title = "Active Servers") {
                        activeServers.forEachIndexed { index, server ->
                            McpServerCard(
                                server = server,
                                onToggle = { enabled -> onToggleEnabled(server, enabled) },
                                onEdit = { onServerClick(server) },
                                onDelete = { onDelete(server) }
                            )
                            if (index < activeServers.size - 1) {
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

            if (disabledServers.isNotEmpty()) {
                item {
                    SettingsSectionCard(title = "Disabled Servers") {
                        disabledServers.forEachIndexed { index, server ->
                            McpServerCard(
                                server = server,
                                onToggle = { enabled -> onToggleEnabled(server, enabled) },
                                onEdit = { onServerClick(server) },
                                onDelete = { onDelete(server) }
                            )
                            if (index < disabledServers.size - 1) {
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

            item {
                Spacer(Modifier.height(16.dp))
                McpFormatGuide()
            }
        }
    }
}
