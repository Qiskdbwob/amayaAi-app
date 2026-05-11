package com.amaya.intelligence.ui.components.remote

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.amaya.intelligence.ui.components.shared.SessionConnectedPill

/**
 * Windows Bridge adapter over the shared [SessionConnectedPill] — shows a pill
 * above the welcome greeting while the bridge session is live.
 */
@Composable
fun WindowsBridgeWelcomePill(
    state: WindowsBridgeChatUiState,
    modifier: Modifier = Modifier
) {
    if (!state.shouldShowBanner) return
    val accent = when {
        state.isPaused -> MaterialTheme.colorScheme.error
        state.isAgentControlEnabled -> Color(0xFFF39C12)
        else -> Color(0xFF2ECC71)
    }
    SessionConnectedPill(
        label = "Windows connected · ${state.statusLabel}",
        icon = Icons.Default.DesktopWindows,
        accent = accent,
        modifier = modifier
    )
}
