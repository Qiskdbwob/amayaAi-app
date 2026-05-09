package com.amaya.intelligence.ui.screens.chat.bridge

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.screens.chat.shared.ChatScreen
import com.amaya.intelligence.ui.screens.chat.shared.windowsBridgeChatScreenConfig
import com.amaya.intelligence.ui.viewmodels.ChatViewModel

@Composable
fun WindowsBridgeChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val config = windowsBridgeChatScreenConfig(
        onExit = onExit,
        onNavigateToSettings = onNavigateToSettings,
        onToolAccept = { execution -> viewModel.respondToToolInteraction(execution.toolCallId, true) },
        onToolDecline = { execution -> viewModel.respondToToolInteraction(execution.toolCallId, false) }
    )

    ChatScreen(
        viewModel = viewModel,
        isRemoteModeOverride = true,
        config = config,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToWorkspace = onNavigateToWorkspace,
        onExit = onExit
    )
}
