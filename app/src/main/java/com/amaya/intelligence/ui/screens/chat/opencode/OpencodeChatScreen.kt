package com.amaya.intelligence.ui.screens.chat.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.bridge.AgentModes
import com.amaya.intelligence.impl.ide.opencode.OpencodePermissionRequest
import com.amaya.intelligence.impl.ide.opencode.OpencodeSessionForegroundService
import com.amaya.intelligence.ui.screens.chat.shared.ChatScreen
import com.amaya.intelligence.ui.screens.chat.shared.remoteChatScreenConfig
import com.amaya.intelligence.ui.viewmodels.ChatViewModel
import com.amaya.intelligence.ui.viewmodels.opencode.OpencodeChatPanelViewModel

/**
 * Opencode chat surface. Re-uses the shared [ChatScreen] and overlays opencode
 * specific chrome:
 *   - a Plan / Build mode pill under the top app bar, and
 *   - a permission approval card when opencode requests a tool permission.
 *
 * Foreground service is started to keep the bridge WS alive while we're here.
 */
@Composable
fun OpencodeChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    panelViewModel: OpencodeChatPanelViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val panelState by panelViewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.switchMode(IntelligenceSessionManager.SessionMode.OPENCODE)
        OpencodeSessionForegroundService.start(
            context,
            title = "Opencode session",
            text = when (panelState.mode) {
                AgentModes.PLAN -> "Plan mode — menjaga chat opencode tetap aktif"
                else -> "Build mode — menjaga chat opencode tetap aktif"
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.switchMode(IntelligenceSessionManager.SessionMode.LOCAL)
            OpencodeSessionForegroundService.stop(context)
        }
    }
    LaunchedEffect(panelState.mode) {
        OpencodeSessionForegroundService.update(
            context,
            title = "Opencode session",
            text = when (panelState.mode) {
                AgentModes.PLAN -> "Plan mode aktif"
                else -> "Build mode aktif"
            }
        )
    }

    val config = remoteChatScreenConfig(
        onExit = onExit,
        onNavigateToSettings = onNavigateToSettings,
        onToolAccept = { _ -> panelViewModel.approvePermission() },
        onToolDecline = { _ -> panelViewModel.rejectPermission() }
    ).copy(
        selectedAgentFallbackLabel = "Select Opencode Model",
        streamingLabel = "Opencode streaming",
        idleLabel = "Opencode ready"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreen(
            viewModel = viewModel,
            isRemoteModeOverride = true,
            config = config,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToWorkspace = onNavigateToWorkspace,
            onExit = onExit,
            sessionDisconnectName = "Opencode"
        )

        val topOffset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 88.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(top = topOffset, start = 16.dp, end = 16.dp)
                .zIndex(10f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OpencodeModeBar(
                mode = panelState.mode,
                isRuntimeReady = panelState.isRuntimeReady,
                onModeChange = panelViewModel::setMode
            )

            panelState.pendingPermission?.let { request ->
                OpencodePermissionCard(
                    request = request,
                    onApproveOnce = panelViewModel::approvePermission,
                    onApproveAlways = panelViewModel::approvePermissionAlways,
                    onReject = panelViewModel::rejectPermission
                )
            }
        }
    }
}

@Composable
private fun OpencodeModeBar(
    mode: String,
    isRuntimeReady: Boolean,
    onModeChange: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.wrapContentSizeFillWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModePill(
                label = "Plan",
                selected = mode == AgentModes.PLAN,
                enabled = isRuntimeReady,
                onClick = { onModeChange(AgentModes.PLAN) }
            )
            ModePill(
                label = "Build",
                selected = mode == AgentModes.BUILD,
                enabled = isRuntimeReady,
                onClick = { onModeChange(AgentModes.BUILD) }
            )
            if (!isRuntimeReady) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "runtime offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surface
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = bg,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = fg,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun OpencodePermissionCard(
    request: OpencodePermissionRequest,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Opencode needs approval",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            val title = request.title.ifBlank { request.kind ?: "Tool call" }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            request.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReject,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) { Text("Reject") }
                TextButton(onClick = onApproveAlways) { Text("Always") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onApproveOnce,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Allow once") }
            }
        }
    }
}

private fun Modifier.wrapContentSizeFillWidth(): Modifier = this.fillMaxWidth()
