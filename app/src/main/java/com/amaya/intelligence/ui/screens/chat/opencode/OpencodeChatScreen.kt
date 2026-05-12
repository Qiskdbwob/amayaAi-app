package com.amaya.intelligence.ui.screens.chat.opencode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.ui.screens.chat.shared.ChatScreen
import com.amaya.intelligence.ui.screens.chat.shared.remoteChatScreenConfig
import com.amaya.intelligence.ui.viewmodels.ChatViewModel

/**
 * Opencode chat — rides on the shared [ChatScreen] but flips session mode to
 * OPENCODE so the delegate in [com.amaya.intelligence.di.IntelligenceModule]
 * routes events to [com.amaya.intelligence.impl.ide.opencode.services.OpencodeIntelligenceService].
 */
@Composable
fun OpencodeChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        viewModel.switchMode(IntelligenceSessionManager.SessionMode.OPENCODE)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.switchMode(IntelligenceSessionManager.SessionMode.LOCAL)
        }
    }

    val config = remoteChatScreenConfig(
        onExit = onExit,
        onNavigateToSettings = onNavigateToSettings,
        onToolAccept = { execution -> viewModel.respondToToolInteraction(execution.toolCallId, true) },
        onToolDecline = { execution -> viewModel.respondToToolInteraction(execution.toolCallId, false) }
    ).copy(
        selectedAgentFallbackLabel = "Select Opencode Agent",
        streamingLabel = "Opencode streaming",
        idleLabel = "Opencode ready"
    )

    ChatScreen(
        viewModel = viewModel,
        isRemoteModeOverride = true,
        config = config,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToWorkspace = onNavigateToWorkspace,
        onExit = onExit,
        sessionDisconnectName = "Opencode"
    )
}
