package com.amaya.intelligence.ui.screens.chat.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.models.ChatUiState
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.domain.models.RemoteWorkspace
import com.amaya.intelligence.ui.components.remote.RemoteSessionPill
import com.amaya.intelligence.ui.components.remote.WindowsBridgeChatUiState
import com.amaya.intelligence.ui.components.remote.WindowsBridgeWelcomePill
import com.amaya.intelligence.ui.components.shared.ConversationSkeleton
import com.amaya.intelligence.ui.components.shared.WelcomeScreen

@Composable
fun ChatEmptyContent(
    isRemoteMode: Boolean,
    connectionState: ConnectionState,
    uiState: ChatUiState,
    headerDp: Dp,
    bottomDp: Dp,
    drawerOpen: Boolean,
    onInputTextChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onNavigateToWorkspace: () -> Unit,
    workspaces: List<RemoteWorkspace>,
    bridgeState: WindowsBridgeChatUiState = WindowsBridgeChatUiState(),
    isStreaming: Boolean = false
) {
    val isBridgeMode =
        uiState.sessionMode == IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE

    if (isRemoteMode && !isBridgeMode && connectionState != ConnectionState.CONNECTED &&
        uiState.messages.isEmpty()
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .padding(top = headerDp, bottom = bottomDp)
                .then(if (!drawerOpen) Modifier.imePadding() else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (connectionState == ConnectionState.CONNECTING)
                        "Connecting to Remote Session..."
                    else "Disconnected. Trying to reconnect...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = headerDp, bottom = bottomDp)
            .then(if (!drawerOpen) Modifier.imePadding() else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isLoading ||
            (isRemoteMode && !isBridgeMode && connectionState == ConnectionState.CONNECTING)
        ) {
            ConversationSkeleton()
        } else {
            val headerSlot: (@Composable () -> Unit)? = when {
                isBridgeMode && bridgeState.shouldShowBanner -> {
                    { WindowsBridgeWelcomePill(state = bridgeState) }
                }
                isRemoteMode && !isBridgeMode && connectionState != ConnectionState.DISCONNECTED -> {
                    {
                        RemoteSessionPill(
                            sessionMode = uiState.sessionMode,
                            connectionState = connectionState,
                            isStreaming = isStreaming
                        )
                    }
                }
                else -> null
            }
            WelcomeScreen(
                onPromptClick = onInputTextChange,
                currentWorkspace = uiState.workspacePath,
                onNewProjectClick = onNavigateToWorkspace,
                workspaces = workspaces,
                onWorkspaceClick = onNavigateToWorkspace,
                showWorkspaceChip = !isBridgeMode,
                header = headerSlot
            )
        }
    }
}
