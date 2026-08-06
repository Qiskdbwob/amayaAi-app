package com.amaya.intelligence.ui.components.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact banner shown at the top of ChatScreen when Windows Bridge is connected.
 * Stateless — receives state + callbacks from the caller.
 */
@Composable
fun WindowsBridgeConnectionBanner(
    state: WindowsBridgeChatUiState,
    onViewScreen: () -> Unit,
    onToggleAgentControl: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.shouldShowBanner,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status dot
                val dotColor = when {
                    state.isPaused -> Color(0xFFE74C3C)
                    state.isAgentControlEnabled -> Color(0xFFF39C12)
                    state.isConnected -> Color(0xFF2ECC71)
                    else -> Color.Gray
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                // Label
                Text(
                    text = "Windows · ${state.statusLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                // View Screen
                IconButton(
                    onClick = onViewScreen,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Screenshot,
                        contentDescription = "View Screen",
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Agent Control chip
                val acColor = if (state.isAgentControlEnabled) {
                    Color(0xFFF39C12)
                } else {
                    MaterialTheme.colorScheme.outline
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = acColor.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { onToggleAgentControl() }
                ) {
                    Text(
                        text = if (state.isAgentControlEnabled) "AC" else "VO",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = acColor
                    )
                }

                // Emergency stop
                if (state.isConnected || state.isPaused) {
                    IconButton(
                        onClick = onEmergencyStop,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Emergency Stop",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
