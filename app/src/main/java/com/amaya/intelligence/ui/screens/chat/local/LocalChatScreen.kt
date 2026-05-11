package com.amaya.intelligence.ui.screens.chat.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeAgentControlDialog
import com.amaya.intelligence.ui.components.remote.WindowsBridgeApprovalCard
import com.amaya.intelligence.ui.components.remote.WindowsBridgeChatPanelViewModel
import com.amaya.intelligence.ui.components.remote.WindowsBridgeConnectionBanner
import com.amaya.intelligence.ui.screens.chat.shared.ChatScreen
import com.amaya.intelligence.ui.screens.chat.shared.localChatScreenConfig
import com.amaya.intelligence.ui.viewmodels.ChatViewModel

@Composable
fun LocalChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    bridgeViewModel: WindowsBridgeChatPanelViewModel = hiltViewModel(),
    activeReminderCount: Int = -1,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkspace: () -> Unit = {},
    onNavigateToRemoteSession: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val config = localChatScreenConfig(
        onClearConversation = { viewModel.clearConversation() },
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToRemoteSession = onNavigateToRemoteSession
    )

    val bridgeState by bridgeViewModel.state.collectAsState()
    val context = LocalContext.current
    var showAgentControlDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main chat screen fills the entire area
        ChatScreen(
            viewModel = viewModel,
            bridgeViewModel = bridgeViewModel,
            activeReminderCount = activeReminderCount,
            config = config,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToWorkspace = onNavigateToWorkspace,
            onNavigateToRemoteSession = onNavigateToRemoteSession,
            onExit = onExit
        )

        // Windows Bridge overlay: banner + approval card, positioned below the header
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topOffset = statusBarHeight + 88.dp // below the floating top bar

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(top = topOffset)
                .zIndex(10f)
        ) {
            // Connection banner: tapping "view screen" opens the dedicated bridge
            // chat activity so the user can interact with tools in place.
            WindowsBridgeConnectionBanner(
                state = bridgeState,
                onViewScreen = {
                    com.amaya.intelligence.ui.activities.bridge.WindowsBridgeChatActivity.start(context)
                },
                onToggleAgentControl = {
                    if (!bridgeState.isAgentControlEnabled) {
                        showAgentControlDialog = true
                    } else {
                        bridgeViewModel.disableAgentControl()
                    }
                },
                onEmergencyStop = { bridgeViewModel.emergencyStop() }
            )

            // Pending approval card
            bridgeState.pendingApproval?.let { request ->
                WindowsBridgeApprovalCard(
                    request = request,
                    onApprove = { bridgeViewModel.approveRequest() },
                    onReject = { bridgeViewModel.rejectRequest() }
                )
            }
        }
    }

    // Agent Control confirmation dialog
    if (showAgentControlDialog) {
        WindowsBridgeAgentControlDialog(
            onConfirm = {
                showAgentControlDialog = false
                bridgeViewModel.confirmEnableAgentControl()
            },
            onDismiss = { showAgentControlDialog = false }
        )
    }
}
