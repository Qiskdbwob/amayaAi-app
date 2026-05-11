package com.amaya.intelligence.ui.components.remote

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.ai.displayName
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.ui.components.shared.SessionConnectedPill

/**
 * Pill rendered above the welcome greeting while a remote IDE session
 * (Antigravity, Cursor, Windsurf, …) is connected. Uses the shared
 * [SessionConnectedPill] so every mode keeps the same visual language.
 */
@Composable
fun RemoteSessionPill(
    sessionMode: IntelligenceSessionManager.SessionMode,
    connectionState: ConnectionState,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    if (sessionMode == IntelligenceSessionManager.SessionMode.LOCAL ||
        sessionMode == IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE) return

    val statusLabel = when (connectionState) {
        ConnectionState.CONNECTED -> if (isStreaming) "Streaming" else "Connected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.DISCONNECTED -> return
    }
    val accent = when (connectionState) {
        ConnectionState.CONNECTED ->
            if (isStreaming) MaterialTheme.colorScheme.primary else Color(0xFF2ECC71)
        ConnectionState.CONNECTING -> Color(0xFF3498DB)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }

    SessionConnectedPill(
        label = "${sessionMode.displayName()} · $statusLabel",
        icon = Icons.Default.Cloud,
        accent = accent,
        modifier = modifier
    )
}
