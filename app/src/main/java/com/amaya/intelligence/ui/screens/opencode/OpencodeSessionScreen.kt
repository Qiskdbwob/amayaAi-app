package com.amaya.intelligence.ui.screens.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.impl.ide.opencode.OpencodeMcpSummary
import com.amaya.intelligence.impl.ide.opencode.OpencodeRuntimeSnapshot
import com.amaya.intelligence.impl.ide.opencode.OpencodeSessionSummary
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.viewmodels.opencode.OpencodeViewModel

/**
 * Landing screen for the Opencode runtime. Shows runtime health, provider/model
 * inventory, MCP state, active sessions, and offers entry to chat + settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpencodeSessionScreen(
    viewModel: OpencodeViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onPairBridge: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val colors = opencodeSessionColors()

    Scaffold(containerColor = Color.Transparent) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))

                RuntimeSummaryCard(
                    runtime = state.runtime,
                    onStart = viewModel::startRuntime,
                    onStop = viewModel::stopRuntime,
                    onRestart = viewModel::restartRuntime,
                    onPairBridge = onPairBridge
                )

                Section(title = "Providers & Models") {
                    if (state.providers.isEmpty()) {
                        PlaceholderRow(
                            icon = Icons.Default.Tune,
                            title = "No providers configured",
                            subtitle = "Edit ~/.config/opencode/opencode.json on your PC to add providers.",
                            isFirst = true,
                            isLast = true
                        )
                    } else {
                        val lastIndex = state.providers.lastIndex
                        state.providers.forEachIndexed { index, entry ->
                            ProviderRow(
                                providerId = entry.providerId,
                                displayName = entry.displayName,
                                modelCount = entry.modelCount,
                                defaultModelId = entry.defaultModelId,
                                isFirst = index == 0,
                                isLast = index == lastIndex
                            )
                            if (index != lastIndex) RowDivider()
                        }
                    }
                }

                Section(title = "MCP Servers") {
                    if (state.mcp.isEmpty()) {
                        PlaceholderRow(
                            icon = Icons.Default.Extension,
                            title = "No MCP servers",
                            subtitle = "Add MCP servers in your opencode.json to surface them here.",
                            isFirst = true,
                            isLast = true
                        )
                    } else {
                        val lastIndex = state.mcp.lastIndex
                        state.mcp.forEachIndexed { index, server ->
                            McpRow(
                                server = server,
                                isFirst = index == 0,
                                isLast = index == lastIndex
                            )
                            if (index != lastIndex) RowDivider()
                        }
                    }
                }

                Section(title = "Sessions") {
                    if (state.sessions.isEmpty()) {
                        PlaceholderRow(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = "No opencode sessions yet",
                            subtitle = "Open chat to start one; opencode stores history on your PC.",
                            isFirst = true,
                            isLast = true
                        )
                    } else {
                        val lastIndex = state.sessions.lastIndex
                        state.sessions.take(5).forEachIndexed { index, session ->
                            SessionRow(
                                session = session,
                                isFirst = index == 0,
                                isLast = index == (state.sessions.size - 1).coerceAtMost(4)
                            )
                            if (index != lastIndex.coerceAtMost(4)) RowDivider()
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onOpenChat,
                        enabled = state.runtime.isReady,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open chat", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Settings", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(64.dp))
            }

            TopAppBar(
                title = { Text("Opencode", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { SettingsBackButton(onClick = onBack) },
                actions = {
                    com.amaya.intelligence.ui.components.shared.AmayaTopBarButton(
                        icon = Icons.Default.Refresh,
                        onClick = viewModel::refreshAll,
                        contentDescription = "Refresh",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                modifier = Modifier.statusBarsPadding()
            )
        }
    }
}

// ── Cards / rows ────────────────────────────────────────────────────────────

@Composable
private fun RuntimeSummaryCard(
    runtime: OpencodeRuntimeSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onPairBridge: () -> Unit
) {
    val colors = opencodeSessionColors()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.groupSurface,
        border = BorderStroke(0.7.dp, colors.border),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = runtime.status)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        runtime.statusLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.primaryText
                    )
                    Text(
                        runtime.detailLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            runtime.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !runtime.isReady && !runtime.isStarting,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onRestart,
                    enabled = runtime.isReady,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onStop,
                    enabled = runtime.isReady || runtime.isStarting,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (runtime.status == "stopped" || runtime.status == "error") {
                Surface(
                    onClick = onPairBridge,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Pair a Windows Bridge to use opencode remotely.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    providerId: String,
    displayName: String,
    modelCount: Int,
    defaultModelId: String?,
    isFirst: Boolean,
    isLast: Boolean
) {
    val colors = opencodeSessionColors()
    val shape = rowShape(isFirst, isLast)
    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(imageVector = Icons.Default.Code, tint = colors.iconTint, bg = colors.iconBackground)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    color = colors.primaryText
                )
                val subtitle = buildString {
                    append(providerId)
                    append(" · ")
                    append("$modelCount model")
                    if (modelCount != 1) append("s")
                    defaultModelId?.takeIf { it.isNotBlank() }?.let {
                        append(" · default $it")
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun McpRow(
    server: OpencodeMcpSummary,
    isFirst: Boolean,
    isLast: Boolean
) {
    val colors = opencodeSessionColors()
    val shape = rowShape(isFirst, isLast)
    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(imageVector = Icons.Default.Extension, tint = colors.iconTint, bg = colors.iconBackground)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    color = colors.primaryText
                )
                val subtitle = buildString {
                    append(server.type)
                    append(" · ")
                    append(if (server.connected) "connected" else "disconnected")
                    if (server.toolCount > 0) append(" · ${server.toolCount} tools")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.secondaryText
                )
            }
            StatusDot(status = if (server.connected) "ready" else "stopped")
        }
    }
}

@Composable
private fun SessionRow(
    session: OpencodeSessionSummary,
    isFirst: Boolean,
    isLast: Boolean
) {
    val colors = opencodeSessionColors()
    val shape = rowShape(isFirst, isLast)
    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(imageVector = Icons.Default.Bolt, tint = colors.iconTint, bg = colors.iconBackground)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title?.takeIf { it.isNotBlank() } ?: session.sessionId.take(8),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = buildString {
                    session.agent?.takeIf { it.isNotBlank() }?.let { append("agent=$it") }
                    session.modelId?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }.ifBlank { session.sessionId }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PlaceholderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isFirst: Boolean,
    isLast: Boolean
) {
    val colors = opencodeSessionColors()
    val shape = rowShape(isFirst, isLast)
    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(imageVector = icon, tint = colors.iconTint, bg = colors.iconBackground)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    color = colors.primaryText
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colors.secondaryText
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = opencodeSessionColors()
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
    val colors = opencodeSessionColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = colors.separator,
        thickness = 0.7.dp
    )
}

@Composable
private fun IconBadge(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    bg: Color
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = imageVector, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "ready" -> Color(0xFF34C759)
        "starting" -> Color(0xFFFF9500)
        "degraded" -> Color(0xFFFFCC00)
        "error" -> Color(0xFFFF3B30)
        else -> Color(0xFF8E8E93)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun rowShape(isFirst: Boolean, isLast: Boolean) = when {
    isFirst && isLast -> RoundedCornerShape(16.dp)
    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    else -> RoundedCornerShape(0.dp)
}

private data class OpencodeSessionColors(
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
private fun opencodeSessionColors(): OpencodeSessionColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        OpencodeSessionColors(
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
        OpencodeSessionColors(
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

private fun OpencodeRuntimeSnapshot.statusLabel(): String = when (status) {
    "ready" -> "Opencode ready"
    "starting" -> "Starting opencode…"
    "stopped" -> "Opencode stopped"
    "degraded" -> "Opencode degraded"
    "error" -> "Opencode error"
    else -> status
}

private fun OpencodeRuntimeSnapshot.detailLabel(): String {
    val parts = mutableListOf<String>()
    baseUrl?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    version?.takeIf { it.isNotBlank() }?.let { parts.add("v$it") }
    binaryPath?.takeIf { it.isNotBlank() }?.let { parts.add("binary=${shorten(it)}") }
    return if (parts.isEmpty()) "Pair a bridge and start the runtime to begin." else parts.joinToString(" · ")
}

private fun shorten(path: String): String {
    if (path.length <= 42) return path
    return "…" + path.takeLast(40)
}
