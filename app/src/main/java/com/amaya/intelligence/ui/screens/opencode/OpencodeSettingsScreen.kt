package com.amaya.intelligence.ui.screens.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.viewmodels.opencode.OpencodeViewModel

/**
 * Opencode-specific settings screen. Exposes runtime knobs and read-only views
 * into config/MCP. Editing opencode.json itself stays on the user's PC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpencodeSettingsScreen(
    viewModel: OpencodeViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val colors = opencodeSettingsColors()

    LaunchedEffect(Unit) {
        viewModel.loadConfig()
    }

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0.dp)) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(Color.Transparent)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(
                    Modifier
                        .statusBarsPadding()
                        .height(com.amaya.intelligence.ui.screens.amaya.AmayaGroupedSettingsTokens.screenContentTopSpacer)
                )

                Section(title = "Runtime") {
                    SettingsRow(
                        icon = Icons.Default.Power,
                        title = "Status",
                        subtitle = state.runtime.status.replaceFirstChar { it.uppercase() } +
                            (state.runtime.baseUrl?.let { " · $it" } ?: ""),
                        isFirst = true,
                        isLast = false,
                        trailing = {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = viewModel::refreshAll
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Default.Folder,
                        title = "Config path",
                        subtitle = state.configPath ?: "~/.config/opencode",
                        isFirst = false,
                        isLast = false,
                        onClick = viewModel::loadConfig
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Default.HealthAndSafety,
                        title = "Health check",
                        subtitle = state.runtime.lastError?.takeIf { it.isNotBlank() }
                            ?: "Ping /global/health",
                        isFirst = false,
                        isLast = true,
                        onClick = viewModel::refreshAll
                    )
                }

                Section(title = "Providers") {
                    if (state.providers.isEmpty()) {
                        SettingsRow(
                            icon = Icons.Default.Code,
                            title = "No providers",
                            subtitle = "Configure providers in opencode.json.",
                            isFirst = true,
                            isLast = true,
                            onClick = viewModel::refreshAll
                        )
                    } else {
                        val lastIndex = state.providers.lastIndex
                        state.providers.forEachIndexed { index, p ->
                            SettingsRow(
                                icon = Icons.Default.Code,
                                title = p.displayName,
                                subtitle = "${p.providerId} · ${p.modelCount} model${if (p.modelCount == 1) "" else "s"}",
                                isFirst = index == 0,
                                isLast = index == lastIndex,
                                onClick = viewModel::refreshAll
                            )
                            if (index != lastIndex) RowDivider()
                        }
                    }
                }

                Section(title = "MCP Servers") {
                    if (state.mcp.isEmpty()) {
                        SettingsRow(
                            icon = Icons.Default.Extension,
                            title = "None configured",
                            subtitle = "Add MCP servers via opencode.json.",
                            isFirst = true,
                            isLast = true,
                            onClick = viewModel::refreshAll
                        )
                    } else {
                        val lastIndex = state.mcp.lastIndex
                        state.mcp.forEachIndexed { index, server ->
                            SettingsRow(
                                icon = Icons.Default.Extension,
                                title = server.name,
                                subtitle = "${server.type} · " +
                                    (if (server.connected) "connected" else "disconnected") +
                                    (if (server.toolCount > 0) " · ${server.toolCount} tools" else ""),
                                isFirst = index == 0,
                                isLast = index == lastIndex,
                                onClick = viewModel::refreshAll
                            )
                            if (index != lastIndex) RowDivider()
                        }
                    }
                }

                Section(title = "Advanced") {
                    SettingsRow(
                        icon = Icons.Default.Shield,
                        title = "Privacy",
                        subtitle = "Secrets in config are redacted before reaching your device.",
                        isFirst = true,
                        isLast = false,
                        onClick = {}
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Default.CloudSync,
                        title = "Sessions",
                        subtitle = "${state.sessions.size} session${if (state.sessions.size == 1) "" else "s"} stored on PC",
                        isFirst = false,
                        isLast = false,
                        onClick = viewModel::refreshAll
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = "View config",
                        subtitle = if (state.configJson != null) "Loaded" else "Tap to load sanitized config",
                        isFirst = false,
                        isLast = true,
                        onClick = viewModel::loadConfig
                    )
                }

                state.configJson?.let {
                    ConfigPreviewCard(it)
                }

                Spacer(Modifier.height(64.dp))
            }

            TopAppBar(
                title = { Text("Opencode Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { SettingsBackButton(onClick = onNavigateBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding()
            )
        }
    }
}

@Composable
private fun ConfigPreviewCard(json: String) {
    val colors = opencodeSettingsColors()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.groupSurface,
        border = BorderStroke(0.7.dp, colors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Sanitized config (read-only)",
                style = MaterialTheme.typography.labelMedium,
                color = colors.headerText
            )
            Spacer(Modifier.height(8.dp))
            Text(
                json.take(2_000) + if (json.length > 2_000) "\n…" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.primaryText
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isFirst: Boolean,
    isLast: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = opencodeSettingsColors()
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(16.dp)
        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = colors.iconTint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = colors.secondaryText,
                    maxLines = 2
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = opencodeSettingsColors()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.headerText,
            modifier = Modifier.padding(start = 16.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.groupSurface,
            border = BorderStroke(0.7.dp, colors.border),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun RowDivider() {
    val colors = opencodeSettingsColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = colors.separator,
        thickness = 0.7.dp
    )
}

private data class OpencodeSettingsColors(
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
private fun opencodeSettingsColors(): OpencodeSettingsColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        OpencodeSettingsColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            separator = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f)
        )
    } else {
        OpencodeSettingsColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            headerText = Color(0xFF3C3C43).copy(alpha = 0.52f)
        )
    }
}
