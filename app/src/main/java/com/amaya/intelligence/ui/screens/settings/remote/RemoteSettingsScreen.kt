package com.amaya.intelligence.ui.screens.settings.remote

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.ui.components.shared.SettingsBackButton

/**
 * Hub screen that lists per-provider remote settings. Individual providers own
 * their own detail screens (`OpencodeSettingsScreen`, future
 * `WindowsBridgeSettingsScreen`, etc.) — this entry stays small on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToOpencode: () -> Unit,
    onNavigateToWindowsBridge: () -> Unit = {},
    onNavigateToAntigravity: () -> Unit = {}
) {
    val colors = remoteSettingsColors()
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0.dp)) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(Color.Transparent)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(Modifier.statusBarsPadding().height(52.dp))

                Section("Providers") {
                    ProviderRow(
                        icon = Icons.Default.Code,
                        title = "Opencode",
                        subtitle = "Runtime, providers, MCP servers, sessions",
                        isFirst = true,
                        isLast = false,
                        onClick = onNavigateToOpencode
                    )
                    Divider()
                    ProviderRow(
                        icon = Icons.Default.DesktopWindows,
                        title = "Windows Bridge",
                        subtitle = "OS tools, input helpers, audit log",
                        isFirst = false,
                        isLast = false,
                        onClick = onNavigateToWindowsBridge
                    )
                    Divider()
                    ProviderRow(
                        icon = Icons.Default.Terminal,
                        title = "Antigravity",
                        subtitle = "Google DeepMind IDE bridge",
                        isFirst = false,
                        isLast = true,
                        onClick = onNavigateToAntigravity
                    )
                }

                Section("Shared") {
                    ProviderRow(
                        icon = Icons.Default.Hub,
                        title = "Trusted Devices",
                        subtitle = "Manage paired Windows bridges (coming soon)",
                        isFirst = true,
                        isLast = true,
                        onClick = {}
                    )
                }

                Spacer(Modifier.height(64.dp))
            }

            TopAppBar(
                title = { Text("Remote Settings", style = MaterialTheme.typography.titleLarge) },
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
private fun ProviderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val colors = remoteSettingsColors()
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
                    color = colors.secondaryText
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = colors.secondaryText.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = remoteSettingsColors()
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
private fun Divider() {
    val colors = remoteSettingsColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = colors.separator,
        thickness = 0.7.dp
    )
}

private data class RemoteSettingsColors(
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
private fun remoteSettingsColors(): RemoteSettingsColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        RemoteSettingsColors(
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
        RemoteSettingsColors(
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
