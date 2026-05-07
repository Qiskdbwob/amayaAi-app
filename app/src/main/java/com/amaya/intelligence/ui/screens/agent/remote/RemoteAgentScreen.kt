package com.amaya.intelligence.ui.screens.agent.remote

import androidx.compose.runtime.Composable
import com.amaya.intelligence.ui.screens.shared.ComingSoonScreen

@Composable
fun RemoteAgentScreen(
    onNavigateBack: () -> Unit
) {
    ComingSoonScreen(
        title = "Agent Mode",
        onNavigateBack = onNavigateBack
    )
}